package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Sequences the layout of a Java compilation unit after the parser has exposed package, import, module, and type nodes.
 *
 * <p>This helper owns only whole-file ordering: source-leading package comments, file-boundary orphan comments before
 * the package, a first type's detached leading documentation (a Javadoc JavaParser left unattached because a blank line
 * separated it from the type, kept above the type rather than floated to the package boundary), the package
 * line, an already-ordered import section, optional module declarations, the top-level declaration block, and trailing
 * orphan comments. It intentionally delegates package declaration text to {@link PackageDeclarationPrinter}, import
 * sorting to {@link ImportSortTransform}, individual imports to {@link ImportDeclarationPrinter}, module declaration
 * formatting to {@link JavaPrinter}, body declaration formatting back to {@link JavaPrinter}, and the top-level
 * declaration list layout — separators, between-type comment interleaving, compact unnamed-class member expansion, and
 * parse-error recovery — to {@link TopLevelDeclarationLayout}. It does not print statements, expressions, raw body
 * preservation, deterministic import ordering, or any single-node package/import behavior itself.
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

    private final CommentTracker comments;

    private final SourceText sourceText;

    private final RecoveredListPlanner recoveredListPlanner;

    private final RecoveredRawGapPrinter importRawGaps;

    private final boolean recoverParseProblems;

    private final PackageDeclarationPrinter packageDeclarations;

    private final ImportDeclarationPrinter importDeclarations;

    private final JavaFormatRule<ModuleDeclaration> moduleDeclarations;

    private final TopLevelDeclarationLayout topLevelDeclarationLayout;

    CompilationUnitPrinter(
            JavaFormatContext context,
            PackageDeclarationPrinter packageDeclarations,
            ImportDeclarationPrinter importDeclarations,
            JavaFormatRule<ModuleDeclaration> moduleDeclarations,
            JavaFormatRule<BodyDeclaration<?>> bodyDeclarations
    ) {
        this.comments = context.comments;
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.importRawGaps = new RecoveredRawGapPrinter(
            context,
            CompilationUnitPrinter::importDeclarationListRecoveryFailure
        );
        this.recoverParseProblems = context.recoverParseProblems;
        this.packageDeclarations = packageDeclarations;
        this.importDeclarations = importDeclarations;
        this.moduleDeclarations = moduleDeclarations;
        this.topLevelDeclarationLayout = new TopLevelDeclarationLayout(context, bodyDeclarations);
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
        // Split orphan comments at the package/imports/module boundary: at/above -> file-boundary content, below ->
        // first-type docs. No prologue => boundary collapses to the first-type line (behavior unchanged).
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
        Optional<Doc> topLevelDeclarations = topLevelDeclarationLayout.topLevelDeclarations(unit);
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
     * Emits the file-boundary orphan comments — those before the first type that begin at or above
     * {@code typeLeadingBoundaryLine} (the last prologue line) — as the pre-{@code package} block.
     *
     * <p>A comment between the package and imports, or between two imports, is still file-boundary content; only one
     * below the whole prologue and before the first type is that type's detached documentation, excluded here and left to
     * {@link #detachedFirstTypeLeadingComments}. With no prologue the boundary collapses to the first-type line, so this
     * slot keeps every before-type orphan.
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
     * Emits the orphan comments that document the first top-level type but that JavaParser left detached (a blank line
     * separated them from the type, so they never attached as its own leading trivia).
     *
     * <p>Selecting orphans that begin strictly below {@code typeLeadingBoundaryLine} (the last prologue line) and before
     * the first type returns the type's documentation to the type while keeping file headers and package docs at the
     * boundary; without it they float above {@code package}, detaching the Javadoc and breaking blank-line idempotence.
     * The caller emits it just before the first type with one hard line, mirroring an attached leading comment so a
     * re-parse re-attaches it and the output is idempotent. With no prologue the boundary collapses to the first-type
     * line, the predicate is unsatisfiable, and such a comment renders through the file-boundary slot instead.
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
     * this boundary and before the first type document that type rather than the file.
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

    private static boolean hasRawGap(RecoveredListPlanner.Plan<?> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    /**
     * Returns the first source line spanned by the unit's top-level types, or {@link Integer#MAX_VALUE} when there is no
     * source-backed type. Shared with {@link TopLevelDeclarationLayout} so the header/footer split and the between-type
     * orphan-comment window read the same boundary.
     */
    static int firstTypeLine(CompilationUnit unit) {
        return unit.getTypes()
                .stream()
                .mapToInt(type -> CommentIndex.beginLine(type, Integer.MAX_VALUE))
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * Returns the last source line spanned by the unit's top-level types, or {@link Integer#MAX_VALUE} when there is no
     * source-backed type (so a range-less unit never clips trailing content). Shared with
     * {@link TopLevelDeclarationLayout}.
     */
    static int lastTypeLine(CompilationUnit unit) {
        int lastSourceBackedLine = unit.getTypes()
                .stream()
                .mapToInt(type -> CommentIndex.endLine(type, Integer.MIN_VALUE))
                .max()
                .orElse(Integer.MIN_VALUE);
        return lastSourceBackedLine == Integer.MIN_VALUE ? Integer.MAX_VALUE : lastSourceBackedLine;
    }

    /**
     * Emits the file footer comments that begin on a line strictly below the final top-level type, each on its own line.
     *
     * <p>A {@code //} comment on the <em>same</em> line as the last type's closing {@code }} is instead handled by
     * {@link #inlineTrailingTypeFooterComment} so it stays inline on that brace line.
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
     * <p>Such a comment belongs to no declaration slot (it begins on the last type's end line, and no following sibling
     * can claim it as leading trivia), so without recovery it is dropped. Attaching it as a line suffix keeps it inline on
     * the {@code }} line, and a re-parse re-renders it here, so the result is idempotent. Only line comments qualify; the
     * column guard in {@link CommentIndex#startsAfterNodeOnSameLine} excludes a comment that opens before the brace.
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
     * <p>Section-level blank lines (between static/ordinary groups and between source-separated chunks) belong here
     * because they depend on neighboring imports; sort-only chunks for leading-commented imports add none. Imports are
     * already sorted into formatter order inside safe chunks, and each import line renders via
     * {@link ImportDeclarationPrinter}.
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
     * <p>Parse-problem units skip transforms, so this keeps parsed imports in source order and lets raw gaps own
     * malformed source and its spacing. Sibling regions include detached leading comments so a raw gap cannot claim the
     * next valid import's chunk header.
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
