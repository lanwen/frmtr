package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Sequences the layout of a Java compilation unit after the parser has exposed package, import, module, and type nodes.
 *
 * <p>This helper owns only whole-file ordering: source-leading package comments, file-boundary orphan comments before
 * the package, a first type's detached leading documentation (a Javadoc JavaParser left unattached because a blank line
 * separated it from the type, kept above the type rather than floated to the package boundary; see #78), the package
 * line, an already-ordered import section, optional module declarations, top-level declarations, compact unnamed-class
 * member expansion, formatter pragma adjacency between top-level declarations, and trailing orphan comments. It
 * intentionally delegates package declaration text to {@link PackageDeclarationPrinter}, import sorting to
 * {@link ImportSortTransform}, individual imports to {@link ImportDeclarationPrinter}, module declaration formatting to
 * {@link JavaPrinter}, and body declaration formatting back to {@link JavaPrinter}. It does not print statements,
 * expressions, raw body preservation, deterministic import ordering, or any single-node package/import behavior itself.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/package-imports-mixed-imports/input.java} and
 * {@code frmtr-core/src/test/resources/format/package-imports-mixed-imports/frmtr-default.output.java}.
 * Module placement with comments is covered by
 * {@code frmtr-core/src/test/resources/format/comment-preservation-module-declaration/input.java} and
 * {@code frmtr-core/src/test/resources/format/comment-preservation-module-declaration/frmtr-default.output.java}; compact
 * unnamed-class expansion is covered by
 * {@code frmtr-core/src/test/resources/format/unnamed-class-compilation-unit/input.java} and
 * {@code frmtr-core/src/test/resources/format/unnamed-class-compilation-unit/frmtr-default.output.java}.
 */
final class CompilationUnitPrinter {

    private static final String IMPORT_DECLARATION_LIST_RECOVERY_FAILURE =
        "Unable to recover Java parse error inside import declaration list: ";

    private static final String TOP_LEVEL_DECLARATION_LIST_RECOVERY_FAILURE =
        "Unable to recover Java parse error inside top-level declaration list: ";

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceOrderedCommentInterleaver<BodyDeclaration<?>> commentInterleaver;

    private final SourceText sourceText;

    private final RecoveredListPlanner recoveredListPlanner;

    private final RecoveredRawGapPrinter importRawGaps;

    private final RecoveredRawGapPrinter topLevelRawGaps;

    private final boolean recoverParseProblems;

    private final PackageDeclarationPrinter packageDeclarations;

    private final ImportDeclarationPrinter importDeclarations;

    private final JavaFormatRule<ModuleDeclaration> moduleDeclarations;

    private final JavaFormatRule<BodyDeclaration<?>> bodyDeclarations;

    private final Predicate<BodyDeclaration<?>> hasPragma;

    CompilationUnitPrinter(
            JavaFormatContext context,
            PackageDeclarationPrinter packageDeclarations,
            ImportDeclarationPrinter importDeclarations,
            JavaFormatRule<ModuleDeclaration> moduleDeclarations,
            JavaFormatRule<BodyDeclaration<?>> bodyDeclarations
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.commentInterleaver = new SourceOrderedCommentInterleaver<>(context.comments);
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.importRawGaps = new RecoveredRawGapPrinter(
            context,
            CompilationUnitPrinter::importDeclarationListRecoveryFailure
        );
        this.topLevelRawGaps = new RecoveredRawGapPrinter(
            context,
            CompilationUnitPrinter::topLevelDeclarationListRecoveryFailure
        );
        this.recoverParseProblems = context.recoverParseProblems;
        this.packageDeclarations = packageDeclarations;
        this.importDeclarations = importDeclarations;
        this.moduleDeclarations = moduleDeclarations;
        this.bodyDeclarations = bodyDeclarations;
        this.hasPragma = context.formatterPragmas::hasPragma;
    }

    /**
     * Prints a whole compilation unit in the formatter's fixed top-level order.
     *
     * <p>The sequence first preserves comments that raw source placed before {@code package}, then emits parser orphan
     * comments that appear before the first type, then package/import/module structure, then top-level types or compact
     * unnamed-class members. Comments after the last type are appended as trailing orphan comments so file-level footer
     * comments remain outside declaration rendering.
     */
    Doc print(CompilationUnit unit) {
        List<Doc> parts = new ArrayList<>();
        boolean hasStructuralParts = false;
        Optional<RecoveredListPlanner.Plan<ImportDeclaration>> importRecoveryPlan = importRecoveryPlan(unit);
        List<RecoveredRawGapPrinter.RawGapRegion> importRawGapRegions = importRecoveryPlan
                .filter(CompilationUnitPrinter::hasRawGap)
                .map(importRawGaps::rawGapRegions)
                .orElse(List.of());
        Doc sourceLeadingComments = packageDeclarations.sourceLeadingCommentsBeforePackage(unit);
        if (sourceLeadingComments != Doc.EMPTY) {
            parts.add(sourceLeadingComments);
            parts.add(Doc.HARD_LINE);
            parts.add(Doc.HARD_LINE);
        }
        int firstTypeLine = firstTypeLine(unit);
        // Orphan comments before the first type split at the structural prologue (package/imports/module): those at or
        // above it stay file-boundary content, those below it document the first type (#78). With no structural prologue
        // there is nothing to float above, so the boundary collapses to the first-type line, the type-leading region is
        // empty, and the file-boundary slot keeps every before-type orphan exactly as it did before.
        int structuralBoundaryLine = structuralBoundaryLine(unit);
        int typeLeadingBoundaryLine = structuralBoundaryLine == Integer.MIN_VALUE
            ? firstTypeLine
            : structuralBoundaryLine;
        Doc orphanComments = orphanCommentsBeforeFirstType(
            unit,
            firstTypeLine,
            typeLeadingBoundaryLine,
            importRawGapRegions
        );
        if (orphanComments != Doc.EMPTY) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(orphanComments);
        }
        unit
                .getPackageDeclaration()
                .ifPresent(packageDeclaration -> {
                    parts.add(packageDeclarations.packageDeclaration(packageDeclaration));
                });
        hasStructuralParts = unit.getPackageDeclaration().isPresent();
        Optional<Doc> imports = imports(unit, importRecoveryPlan, importRawGapRegions);
        if (imports.isPresent()) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(imports.orElseThrow());
            hasStructuralParts = true;
        }
        Optional<ModuleDeclaration> module = unit.getModule();
        module.ifPresent(moduleDeclaration -> {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(moduleDeclarations.format(moduleDeclaration, LayoutContext.root()));
        });
        hasStructuralParts = hasStructuralParts || module.isPresent();
        Optional<Doc> topLevelDeclarations = topLevelDeclarations(unit);
        if (topLevelDeclarations.isPresent()) {
            Doc detachedTypeLeadingComments = detachedFirstTypeLeadingComments(
                unit,
                firstTypeLine,
                typeLeadingBoundaryLine,
                importRawGapRegions
            );
            if (hasStructuralParts) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            if (detachedTypeLeadingComments != Doc.EMPTY) {
                parts.add(detachedTypeLeadingComments);
            }
            Doc inlineFooterComment = inlineTrailingTypeFooterComment(unit);
            parts.add(
                inlineFooterComment == Doc.EMPTY
                    ? topLevelDeclarations.orElseThrow()
                    : Doc.concat(topLevelDeclarations.orElseThrow(), inlineFooterComment)
            );
        }
        Doc trailingOrphanComments = trailingOrphanComments(unit);
        if (trailingOrphanComments != Doc.EMPTY) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
            }
            parts.add(trailingOrphanComments);
        }
        return Doc.label("java.compilationUnit", Doc.concat(parts));
    }

    /**
     * Emits the orphan comments that belong to the file boundary — those before the first type that begin at or above the
     * {@code typeLeadingBoundaryLine} (the last line of the package/imports/module prologue) — as the pre-{@code package}
     * block.
     *
     * <p>An orphan comment that begins <em>below</em> the whole structural prologue but before the first type is the first
     * type's detached leading documentation, not a file header: it is excluded here and emitted by
     * {@link #detachedFirstTypeLeadingComments} so it stays attached to the type it documents (#78). The structural
     * boundary is used rather than the first-type line because a comment between the package and the imports, or between
     * two imports, is still file-boundary content; only a comment under the whole structural prologue and immediately
     * before the first type is type documentation. When there is no structural prologue the caller collapses the boundary
     * to the first-type line, so {@code beginLine < firstTypeLine} already implies {@code beginLine <= boundary} and this
     * slot keeps every before-type orphan exactly as it did before this split existed.
     */
    private Doc orphanCommentsBeforeFirstType(
            CompilationUnit unit,
            int firstTypeLine,
            int typeLeadingBoundaryLine,
            List<RecoveredRawGapPrinter.RawGapRegion> importRawGapRegions
    ) {
        return comments.orphanComments(unit, comment -> CommentIndex.beginLine(comment, Integer.MAX_VALUE) < firstTypeLine
                && CommentIndex.beginLine(comment, Integer.MAX_VALUE) <= typeLeadingBoundaryLine
                && !isContainedByRawGap(comment, importRawGapRegions)
        );
    }

    /**
     * Emits the orphan comments that document the first top-level type but that JavaParser left detached because a blank
     * line separated the comment from the type, so the comment was never attached as the type's own leading trivia (#78).
     *
     * <p>Without this slot those comments fall into {@link #orphanCommentsBeforeFirstType} and are floated above the
     * {@code package} statement, detaching a type's Javadoc from the type and producing non-idempotent blank-line
     * accounting at the license/package boundary. Selecting orphans that begin strictly below {@code typeLeadingBoundaryLine}
     * (the last line of the package/imports/module prologue) and before the first type keeps file headers and package docs
     * at the boundary while returning the type's documentation to the type. The caller emits this immediately before the
     * first type with a single hard line, mirroring an attached leading comment so a re-parse re-attaches it to the type
     * and the output is idempotent.
     *
     * <p>When there is no structural prologue the caller collapses the boundary to the first-type line, which makes
     * {@code beginLine > boundary && beginLine < firstTypeLine} unsatisfiable, so this slot stays empty and a leading
     * comment above the first type renders through the file-boundary slot as before — there is no {@code package} for it to
     * detach from in that shape.
     */
    private Doc detachedFirstTypeLeadingComments(
            CompilationUnit unit,
            int firstTypeLine,
            int typeLeadingBoundaryLine,
            List<RecoveredRawGapPrinter.RawGapRegion> importRawGapRegions
    ) {
        return comments.orphanComments(unit, comment -> CommentIndex.beginLine(comment, Integer.MAX_VALUE) < firstTypeLine
                && CommentIndex.beginLine(comment, Integer.MIN_VALUE) > typeLeadingBoundaryLine
                && !isContainedByRawGap(comment, importRawGapRegions)
        );
    }

    /**
     * Returns the last source line occupied by the structural prologue — the package declaration, imports, and module
     * declaration — or {@link Integer#MIN_VALUE} when none of them is present. Comments that begin on a later line than
     * this boundary and before the first type document that type rather than the file (#78).
     */
    private int structuralBoundaryLine(CompilationUnit unit) {
        int boundary = Integer.MIN_VALUE;
        if (unit.getPackageDeclaration().isPresent()) {
            boundary = Math.max(boundary, CommentIndex.endLine(unit.getPackageDeclaration().orElseThrow(), boundary));
        }
        for (ImportDeclaration importDeclaration : unit.getImports()) {
            boundary = Math.max(boundary, CommentIndex.endLine(importDeclaration, boundary));
        }
        if (unit.getModule().isPresent()) {
            boundary = Math.max(boundary, CommentIndex.endLine(unit.getModule().orElseThrow(), boundary));
        }
        return boundary;
    }

    private boolean isContainedByRawGap(
            Comment comment,
            List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions
    ) {
        try {
            return comment.getRange()
                    .map(sourceText::region)
                    .map(commentRegion -> rawGapRegions.stream().anyMatch(
                            rawGap -> RecoveredRawGapPrinter.contains(rawGap.region(), commentRegion)
                    ))
                    .orElse(false);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Optional<Doc> topLevelDeclarations(CompilationUnit unit) {
        Optional<ClassOrInterfaceDeclaration> compactClass = compactClass(unit);
        if (compactClass.isPresent()) {
            NodeList<BodyDeclaration<?>> members = compactClass.orElseThrow().getMembers();
            return joinedTopLevelDeclarations(
                members,
                members.stream()
                        .map(node -> bodyDeclarations.format(node, LayoutContext.root()))
                        .toList()
            );
        }
        List<BodyDeclaration<?>> declarations = topLevelTypes(unit);
        if (declarations.isEmpty()) {
            return Optional.empty();
        }
        Optional<RecoveredListPlanner.Plan<BodyDeclaration<?>>> recoveryPlan = recoveryPlan(unit, declarations);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return Optional.of(recoveredTopLevelDeclarations(unit, recoveryPlan.orElseThrow()));
        }
        return interleavedTopLevelDeclarations(unit, declarations);
    }

    /**
     * Joins top-level type declarations while restoring file orphan comments that source placed <em>between</em> two
     * types, such as a {@code // Bug Fix: #123} note sitting between two classes.
     *
     * <p>Comments before the first type and after the last type are already emitted by {@code print} (around the
     * package/import block and as the file footer). A comment between two top-level types is a compilation-unit orphan
     * that neither of those handles, so without this interleave it is dropped — most visibly when whitespace is expanded
     * so the comment no longer shares a line with either type. The interleaver places it in source order between the
     * surrounding type docs; claim-coupling keeps the before-first / after-last passes from re-emitting it.
     */
    private Optional<Doc> interleavedTopLevelDeclarations(CompilationUnit unit, List<BodyDeclaration<?>> declarations) {
        List<JavaCommentTrivia> betweenTypeComments = betweenTypeOrphanComments(unit, declarations);
        if (betweenTypeComments.isEmpty()) {
            return joinedTopLevelDeclarations(declarations, declarations.stream().map(node -> bodyDeclarations.format(node, LayoutContext.root())).toList());
        }
        List<Doc> declarationDocs = declarations.stream().map(node -> bodyDeclarations.format(node, LayoutContext.root())).toList();
        List<Doc> parts = commentInterleaver.interleave(
            unit,
            declarations,
            betweenTypeComments,
            (previousSibling, current, index) -> Optional.of(declarationDocs.get(index)),
            new SourceOrderedCommentInterleaver.Spacing<>() {
                @Override
                public int beginLine(BodyDeclaration<?> sibling) {
                    return CommentIndex.beginLine(sibling, Integer.MAX_VALUE);
                }

                @Override
                public int endLine(BodyDeclaration<?> sibling) {
                    return CommentIndex.endLine(sibling, beginLine(sibling));
                }

                @Override
                public Doc separatorBeforeSibling(
                        SourceOrderedCommentInterleaver.PreviousEntry<BodyDeclaration<?>> previous,
                        BodyDeclaration<?> currentSibling
                ) {
                    return previous.kind() == SourceOrderedCommentInterleaver.EntryKind.SIBLING
                        ? topLevelDeclarationSeparator(previous.sibling().orElse(null), currentSibling)
                        : Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
                }

                @Override
                public Doc separatorBeforeComment(
                        SourceOrderedCommentInterleaver.PreviousEntry<BodyDeclaration<?>> previous,
                        JavaCommentTrivia comment
                ) {
                    return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
                }
            }
        );
        return parts.isEmpty() ? Optional.empty() : Optional.of(Doc.concat(parts));
    }

    /**
     * Returns compilation-unit orphan comments that begin after the first top-level type starts and before the last one
     * ends, i.e. comments interleaved between top-level types. The before-first and after-last regions are intentionally
     * excluded because {@code print} already emits them.
     */
    private List<JavaCommentTrivia> betweenTypeOrphanComments(CompilationUnit unit, List<BodyDeclaration<?>> types) {
        int firstTypeLine = firstTypeLine(unit);
        int lastTypeLine = lastTypeLine(unit);
        return commentPlacement.orphanComments(unit)
                .stream()
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) > firstTypeLine
                        && comment.beginLine(Integer.MIN_VALUE) <= lastTypeLine
                        && types.stream().noneMatch(comment::startsInsideLineRange))
                .toList();
    }

    /**
     * Emits a recovered top-level declaration sequence without adding formatter-owned separators around raw gaps.
     *
     * <p>Raw top-level gaps carry the original source spacing between the nearest valid type declarations. This path
     * therefore keeps normal blank-line separation only between adjacent valid top-level declarations while allowing raw
     * islands to own malformed source and the comments fully contained by that source. Package, import, and module
     * declarations stay outside the recovered list boundary.
     */
    private Doc recoveredTopLevelDeclarations(
            CompilationUnit unit,
            RecoveredListPlanner.Plan<BodyDeclaration<?>> plan
    ) {
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = topLevelRawGaps.rawGapRegions(plan);
        topLevelRawGaps.requireRecoverableRawRegions(unit, rawGapRegions);

        List<Doc> contents = new ArrayList<>();
        EntryKind previousEntry = EntryKind.NONE;
        BodyDeclaration<?> previousDeclaration = null;
        int rawGapIndex = 0;
        for (RecoveredListPlanner.Entry<BodyDeclaration<?>> entry : plan.entries()) {
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    BodyDeclaration<?> currentDeclaration = (BodyDeclaration<?>) valid.sibling();
                    appendSeparatorBeforeRecoveredTopLevelDeclaration(
                        contents,
                        previousEntry,
                        previousDeclaration,
                        currentDeclaration
                    );
                    contents.add(bodyDeclarations.format(currentDeclaration, LayoutContext.root()));
                    previousDeclaration = currentDeclaration;
                    previousEntry = EntryKind.VALID_DECLARATION;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        contents.add(topLevelRawGaps.raw(unit, rawRegion, "topLevelDeclarationList"));
                    }
                    previousEntry = rawRegion.trailingBreakReplaced()
                        ? EntryKind.RAW_GAP_WITH_TRAILING_BREAK
                        : EntryKind.RAW_GAP;
                }
            }
        }
        return Doc.concat(contents);
    }

    private void appendSeparatorBeforeRecoveredTopLevelDeclaration(
            List<Doc> contents,
            EntryKind previousEntry,
            BodyDeclaration<?> previousDeclaration,
            BodyDeclaration<?> currentDeclaration
    ) {
        if (contents.isEmpty()) {
            return;
        }
        switch (previousEntry) {
            case VALID_DECLARATION -> contents.add(
                topLevelDeclarationSeparator(previousDeclaration, currentDeclaration)
            );
            case RAW_GAP_WITH_TRAILING_BREAK -> contents.add(Doc.HARD_LINE);
            case NONE, RAW_GAP -> {
                // Raw source already owns the separation before this declaration.
            }
        }
    }

    private Optional<RecoveredListPlanner.Plan<BodyDeclaration<?>>> recoveryPlan(
            CompilationUnit unit,
            List<BodyDeclaration<?>> declarations
    ) {
        if (!recoverParseProblems || !hasRecoverableTopLevelDeclarationListProblem(declarations)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<BodyDeclaration<?>> plan = recoveredListPlanner.plan(
            unit,
            topLevelDeclarationListRegion(declarations),
            declarations,
            declaration -> declaration.getParsed() == Node.Parsedness.PARSED
        );
        if (!plan.isSafe()) {
            throw topLevelDeclarationListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    private SourceRegion topLevelDeclarationListRegion(List<BodyDeclaration<?>> declarations) {
        int beginOffset = Integer.MAX_VALUE;
        int endOffset = Integer.MIN_VALUE;
        try {
            for (BodyDeclaration<?> declaration : declarations) {
                SourceRegion declarationRegion = declaration.getRange()
                        .map(sourceText::region)
                        .filter(region -> region.beginOffset() < region.endOffset())
                        .orElseThrow(() -> new IllegalArgumentException(
                                declaration.getClass().getSimpleName() + " is missing a source range"
                        ));
                beginOffset = Math.min(beginOffset, declarationRegion.beginOffset());
                endOffset = Math.max(endOffset, declarationRegion.endOffset());
            }
            if (beginOffset == Integer.MAX_VALUE || beginOffset >= endOffset) {
                throw new IllegalArgumentException("top-level declaration list has no recoverable source range");
            }
            return sourceText.region(beginOffset, endOffset);
        } catch (IllegalArgumentException exception) {
            throw topLevelDeclarationListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private Optional<Doc> joinedTopLevelDeclarations(
            List<? extends BodyDeclaration<?>> declarations,
            List<Doc> declarationDocs
    ) {
        if (declarationDocs.isEmpty()) {
            return Optional.empty();
        }
        List<Doc> parts = new ArrayList<>();
        for (int i = 0; i < declarationDocs.size(); i++) {
            if (i > 0) {
                parts.add(topLevelDeclarationSeparator(declarations.get(i - 1), declarations.get(i)));
            }
            parts.add(declarationDocs.get(i));
        }
        return Optional.of(Doc.concat(parts));
    }

    private Doc topLevelDeclarationSeparator(BodyDeclaration<?> previous, BodyDeclaration<?> current) {
        if (previous != null && (hasPragma.test(previous) || hasPragma.test(current))) {
            return Doc.HARD_LINE;
        }
        return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
    }

    private static List<BodyDeclaration<?>> topLevelTypes(CompilationUnit unit) {
        List<BodyDeclaration<?>> declarations = new ArrayList<>(unit.getTypes().size());
        declarations.addAll(unit.getTypes());
        return declarations;
    }

    private static boolean hasRecoverableTopLevelDeclarationListProblem(List<BodyDeclaration<?>> declarations) {
        return declarations.stream().anyMatch(
            declaration -> declaration.getParsed() != Node.Parsedness.PARSED
        ) && declarations.stream().anyMatch(declaration -> declaration.getParsed() == Node.Parsedness.PARSED);
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<?> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    /**
     * Detects JavaParser's compact compilation-unit wrapper for unnamed classes.
     *
     * <p>When the compilation unit is exactly one compact class, the source-level declarations are the wrapper's
     * members rather than the wrapper itself. Normal classes keep the wrapper and are dispatched as ordinary top-level
     * body declarations.
     */
    private Optional<ClassOrInterfaceDeclaration> compactClass(CompilationUnit unit) {
        if (
            unit.getTypes().size() != 1
            || !(unit.getTypes().get(0) instanceof ClassOrInterfaceDeclaration declaration)
        ) {
            return Optional.empty();
        }
        return declaration.isCompact() ? Optional.of(declaration) : Optional.empty();
    }

    private static FormatterException topLevelDeclarationListRecoveryFailure(String reason) {
        return new FormatterException(TOP_LEVEL_DECLARATION_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException topLevelDeclarationListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(TOP_LEVEL_DECLARATION_LIST_RECOVERY_FAILURE + reason, cause);
    }

    private enum EntryKind {
        /**
         * No top-level declaration-list entry has been emitted yet.
         */
        NONE,

        /**
         * The previous entry was a normally formatted top-level declaration.
         */
        VALID_DECLARATION,

        /**
         * The previous entry was a raw source island that still owns the following separator.
         */
        RAW_GAP,

        /**
         * The previous entry was a raw source island whose final line break was replaced by formatter-owned output.
         */
        RAW_GAP_WITH_TRAILING_BREAK,
    }

    private int firstTypeLine(CompilationUnit unit) {
        return unit.getTypes()
                .stream()
                .mapToInt(type -> CommentIndex.beginLine(type, Integer.MAX_VALUE))
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private int lastTypeLine(CompilationUnit unit) {
        int lastSourceBackedLine = unit.getTypes()
                .stream()
                .mapToInt(type -> CommentIndex.endLine(type, Integer.MIN_VALUE))
                .max()
                .orElse(Integer.MIN_VALUE);
        return lastSourceBackedLine == Integer.MIN_VALUE ? Integer.MAX_VALUE : lastSourceBackedLine;
    }

    /**
     * Emits the file footer comments that begin on a line strictly below the final top-level type.
     *
     * <p>These footer orphans were always emitted here, each on its own line after the closing {@code }}. A {@code //}
     * comment written on the <em>same</em> line as the last type's closing {@code }} is handled separately by
     * {@link #inlineTrailingTypeFooterComment} so it stays inline on the brace line it trailed in source rather than being
     * pushed to its own line.
     */
    private Doc trailingOrphanComments(CompilationUnit unit) {
        int lastTypeLine = lastTypeLine(unit);
        return comments.orphanComments(
            unit,
            comment -> CommentIndex.beginLine(comment, Integer.MIN_VALUE) > lastTypeLine
        );
    }

    /**
     * Emits, as a {@link Doc#lineSuffix(Doc)}, a {@code //} comment that trails the final top-level type's closing brace on
     * the same source line — e.g. {@code }}{@code // RouteChannel}.
     *
     * <p>Such a comment belongs to no declaration slot: it begins on the last type's end line, so the strict after-line
     * footer query excludes it, and no following sibling exists to claim it as leading trivia, so without recovery it is
     * dropped (#97). It conceptually belongs to the closing-brace line, so it is attached after the top-level declarations
     * as a line suffix and stays inline on the {@code }} line. On re-parse the comment again trails the closing brace on
     * the same line and re-renders here, so the result is idempotent. Only line comments qualify; the column guard in
     * {@link CommentIndex#startsAfterNodeOnSameLine} keeps a comment that opens before the closing brace out of this slot.
     */
    private Doc inlineTrailingTypeFooterComment(CompilationUnit unit) {
        Optional<BodyDeclaration<?>> lastType = lastSourceBackedType(unit);
        if (lastType.isEmpty()) {
            return Doc.EMPTY;
        }
        List<Doc> inlineComments = comments.orphanCommentStatements(
            unit,
            comment -> comment instanceof LineComment
                && CommentIndex.startsAfterNodeOnSameLine(lastType.orElseThrow(), comment)
        );
        if (inlineComments.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.lineSuffix(Doc.concat(Doc.text(" "), Doc.join(Doc.text(" "), inlineComments)));
    }

    /**
     * Returns the top-level type whose source range ends last, i.e. the type the file footer follows. Types without a
     * source range are skipped because a trailing footer comment can only be positioned relative to a source-backed type.
     */
    private Optional<BodyDeclaration<?>> lastSourceBackedType(CompilationUnit unit) {
        return unit.getTypes()
                .stream()
                .filter(type -> type.getRange().isPresent())
                .max(Comparator.comparingInt(type -> CommentIndex.endLine(type, Integer.MIN_VALUE)))
                .map(type -> type);
    }

    /**
     * Builds the import section from already-ordered import chunks.
     *
     * <p>The section-level blank lines between static/ordinary import groups and between source-separated import chunks
     * belong here because they depend on neighboring imports being present. Sort-only chunks for leading-commented
     * imports do not automatically add blank lines. The transform stage has already sorted imports into formatter order
     * inside safe chunks, and rendering each individual import line remains with {@link ImportDeclarationPrinter}.
     */
    private Optional<Doc> imports(
            CompilationUnit unit,
            Optional<RecoveredListPlanner.Plan<ImportDeclaration>> recoveryPlan,
            List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions
    ) {
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return Optional.of(recoveredImports(unit, recoveryPlan.orElseThrow(), rawGapRegions));
        }
        List<ImportChunks.ImportChunk> chunks = ImportChunks.orderedChunks(unit);
        if (chunks.isEmpty()) {
            return Optional.empty();
        }
        List<Doc> parts = new ArrayList<>();
        for (ImportChunks.ImportChunk chunk : chunks) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                if (chunk.separatorBefore()) {
                    parts.add(Doc.HARD_LINE);
                }
            }
            parts.add(importChunk(chunk));
        }
        return Optional.of(Doc.concat(parts));
    }

    /**
     * Emits a recovered import declaration sequence without applying formatter-owned import grouping.
     *
     * <p>Parse-problem compilation units skip transforms, so this path keeps parsed import siblings in source order and
     * lets raw gaps own malformed source and its spacing. Import-specific sibling regions include detached leading
     * comments so a raw gap cannot accidentally claim the next valid import's chunk header.
     */
    private Doc recoveredImports(
            CompilationUnit unit,
            RecoveredListPlanner.Plan<ImportDeclaration> plan,
            List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions
    ) {
        importRawGaps.requireRecoverableRawRegions(unit, rawGapRegions);

        List<Doc> contents = new ArrayList<>();
        ImportEntryKind previousEntry = ImportEntryKind.NONE;
        ImportDeclaration previousImport = null;
        int rawGapIndex = 0;
        for (RecoveredListPlanner.Entry<ImportDeclaration> entry : plan.entries()) {
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    ImportDeclaration currentImport = (ImportDeclaration) valid.sibling();
                    appendSeparatorBeforeRecoveredImport(contents, previousEntry, previousImport, currentImport);
                    contents.add(importDeclarations.importDeclaration(currentImport));
                    previousImport = currentImport;
                    previousEntry = ImportEntryKind.VALID_IMPORT;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        contents.add(importRawGaps.raw(unit, rawRegion, "importDeclarationList"));
                    }
                    previousEntry = rawRegion.trailingBreakReplaced()
                        ? ImportEntryKind.RAW_GAP_WITH_TRAILING_BREAK
                        : ImportEntryKind.RAW_GAP;
                }
            }
        }
        return Doc.concat(contents);
    }

    private void appendSeparatorBeforeRecoveredImport(
            List<Doc> contents,
            ImportEntryKind previousEntry,
            ImportDeclaration previousImport,
            ImportDeclaration currentImport
    ) {
        if (contents.isEmpty()) {
            return;
        }
        switch (previousEntry) {
            case VALID_IMPORT -> {
                contents.add(Doc.HARD_LINE);
                if (ImportChunks.hasSourceSeparatorBefore(previousImport, currentImport)) {
                    contents.add(Doc.HARD_LINE);
                }
            }
            case RAW_GAP_WITH_TRAILING_BREAK -> contents.add(Doc.HARD_LINE);
            case NONE, RAW_GAP -> {
                // Raw source already owns the separation before this import.
            }
        }
    }

    private Optional<RecoveredListPlanner.Plan<ImportDeclaration>> importRecoveryPlan(CompilationUnit unit) {
        List<ImportDeclaration> declarations = unit.getImports();
        if (!recoverParseProblems || !hasRecoverableImportDeclarationListProblem(declarations)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<ImportDeclaration> plan = recoveredListPlanner.plan(
            unit,
            importDeclarationListRegion(declarations),
            declarations,
            declaration -> declaration.getParsed() == Node.Parsedness.PARSED,
            this::importDeclarationRegion
        );
        if (!plan.isSafe()) {
            throw importDeclarationListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    private SourceRegion importDeclarationListRegion(List<ImportDeclaration> declarations) {
        int beginOffset = Integer.MAX_VALUE;
        int endOffset = Integer.MIN_VALUE;
        try {
            for (ImportDeclaration declaration : declarations) {
                SourceRegion declarationRegion = importDeclarationRegion(declaration).orElseThrow(
                    () -> new IllegalArgumentException(
                        declaration.getClass().getSimpleName() + " is missing a source range"
                    )
                );
                beginOffset = Math.min(beginOffset, declarationRegion.beginOffset());
                endOffset = Math.max(endOffset, importDeclarationBoundaryEnd(declaration));
            }
            if (beginOffset == Integer.MAX_VALUE || beginOffset >= endOffset) {
                throw new IllegalArgumentException("import declaration list has no recoverable source range");
            }
            return sourceText.region(beginOffset, endOffset);
        } catch (IllegalArgumentException exception) {
            throw importDeclarationListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private Optional<SourceRegion> importDeclarationRegion(ImportDeclaration declaration) {
        try {
            SourceRegion importRegion = declaration.getRange()
                    .map(sourceText::region)
                    .filter(region -> region.beginOffset() < region.endOffset())
                    .orElseThrow(() -> new IllegalArgumentException("import declaration is missing a source range"));
            int beginOffset = leadingImportComment(declaration)
                    .flatMap(this::commentRegion)
                    .map(SourceRegion::beginOffset)
                    .orElse(importRegion.beginOffset());
            return Optional.of(sourceText.region(beginOffset, importRegion.endOffset()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private int importDeclarationBoundaryEnd(ImportDeclaration declaration) {
        SourceRegion importRegion = declaration.getRange()
                .map(sourceText::region)
                .orElseThrow(() -> new IllegalArgumentException("import declaration is missing a source range"));
        return trailingImportComment(declaration)
                .flatMap(this::commentRegion)
                .map(SourceRegion::endOffset)
                .orElse(importRegion.endOffset());
    }

    private Optional<Comment> leadingImportComment(ImportDeclaration declaration) {
        return declaration.getComment()
                .filter(comment -> CommentIndex.startsBefore(comment, declaration));
    }

    private Optional<Comment> trailingImportComment(ImportDeclaration declaration) {
        return declaration.getComment()
                .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment));
    }

    private Optional<SourceRegion> commentRegion(Comment comment) {
        try {
            return comment.getRange().map(sourceText::region);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static boolean hasRecoverableImportDeclarationListProblem(List<ImportDeclaration> declarations) {
        return declarations.stream().anyMatch(
            declaration -> !isFullyParsed(declaration)
        ) && declarations.stream().anyMatch(CompilationUnitPrinter::isFullyParsed);
    }

    private static boolean isFullyParsed(Node node) {
        return node.stream().allMatch(descendant -> descendant.getParsed() == Node.Parsedness.PARSED);
    }

    private Doc importChunk(ImportChunks.ImportChunk chunk) {
        List<ImportDeclaration> staticImports = chunk.imports()
                .stream()
                .filter(ImportDeclaration::isStatic)
                .toList();
        List<ImportDeclaration> normalImports = chunk.imports()
                .stream()
                .filter(importDeclaration -> !importDeclaration.isStatic())
                .toList();
        List<Doc> blocks = new ArrayList<>();
        if (!staticImports.isEmpty()) {
            blocks.add(
                Doc.join(
                    Doc.HARD_LINE,
                    staticImports.stream().map(importDeclarations::importDeclaration).toList()
                )
            );
        }
        if (!normalImports.isEmpty() && !staticImports.isEmpty()) {
            blocks.add(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE));
        }
        if (!normalImports.isEmpty()) {
            blocks.add(
                Doc.join(
                    Doc.HARD_LINE,
                    normalImports.stream().map(importDeclarations::importDeclaration).toList()
                )
            );
        }
        return Doc.concat(blocks);
    }

    private static FormatterException importDeclarationListRecoveryFailure(String reason) {
        return new FormatterException(IMPORT_DECLARATION_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException importDeclarationListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(IMPORT_DECLARATION_LIST_RECOVERY_FAILURE + reason, cause);
    }

    private enum ImportEntryKind {
        /**
         * No recovered import-list entry has been emitted yet.
         */
        NONE,

        /**
         * The previous entry was a normally formatted valid import declaration.
         */
        VALID_IMPORT,

        /**
         * The previous entry was a raw malformed import-list gap that kept its trailing line break in source text.
         */
        RAW_GAP,

        /**
         * The previous entry was a raw malformed import-list gap whose trailing line break moved to formatter docs.
         */
        RAW_GAP_WITH_TRAILING_BREAK,
    }
}
