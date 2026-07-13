package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Lays out the top-level declaration list of a compilation unit once {@link CompilationUnitPrinter} has emitted the
 * whole-file prologue (package, imports, module, and the file-boundary orphan-comment slots).
 *
 * <p>This helper owns everything that sequences the declarations <em>inside</em> the top-level body: the pragma-aware
 * blank-line separator between adjacent top-level types, the interleaving of file orphan comments that source placed
 * <em>between</em> two types (so a {@code // Bug Fix} note between two classes survives), the compact unnamed-class
 * wrapper expansion that renders the wrapper's members rather than the wrapper, and the parse-error recovery path that
 * lets raw source islands own malformed top-level source and its spacing while keeping normal separation between valid
 * declarations. The boundary exists so {@code print} can consult a single authority for "render the top-level body" and
 * keep its own attention on whole-file ordering; the layout arithmetic for the declaration list does not have to live
 * inline in the file-level sequencer.
 *
 * <p>The helper claims no ownership of the package/import/module structure, the file-boundary or footer orphan-comment
 * slots, or the individual body-declaration text: it formats each declaration through the injected
 * {@link JavaFormatRule} (which routes back to {@link JavaPrinter}), defers raw-gap rendering to
 * {@link RecoveredRawGapPrinter}, recovery planning to {@link RecoveredListPlanner}, and between-type comment
 * interleaving to {@link SourceOrderedCommentInterleaver}. It reports its result as an {@link Optional} {@link Doc} and
 * leaves the caller to decide the separators and hard lines that join it to the rest of the file.
 */
final class TopLevelDeclarationLayout {

    private static final String TOP_LEVEL_DECLARATION_LIST_RECOVERY_FAILURE =
        "Unable to recover Java parse error inside top-level declaration list: ";

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceOrderedCommentInterleaver<BodyDeclaration<?>> commentInterleaver;

    private final SourceText sourceText;

    private final RecoveredListPlanner recoveredListPlanner;

    private final RecoveredRawGapPrinter topLevelRawGaps;

    private final boolean recoverParseProblems;

    private final JavaFormatRule<BodyDeclaration<?>> bodyDeclarations;

    private final Predicate<BodyDeclaration<?>> hasPragma;

    TopLevelDeclarationLayout(
            JavaFormatContext context,
            JavaFormatRule<BodyDeclaration<?>> bodyDeclarations
    ) {
        this.commentPlacement = context.commentPlacementPolicy;
        this.commentInterleaver = new SourceOrderedCommentInterleaver<>(context.comments);
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.topLevelRawGaps = new RecoveredRawGapPrinter(
            context,
            TopLevelDeclarationLayout::topLevelDeclarationListRecoveryFailure
        );
        this.recoverParseProblems = context.recoverParseProblems;
        this.bodyDeclarations = bodyDeclarations;
        this.hasPragma = context.formatterPragmas::hasPragma;
    }

    Optional<Doc> topLevelDeclarations(CompilationUnit unit) {
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
}
