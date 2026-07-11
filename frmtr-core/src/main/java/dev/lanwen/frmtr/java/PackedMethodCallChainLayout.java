package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Greedy-packs a method-call chain onto its first line, then breaks the overflow one segment per line.
 *
 * <p>This helper owns the "packed" chain shapes: the greedy first-line packer that crams the root plus as many
 * {@code .call()} segments as fit before spilling the rest onto indented continuation lines, the object-creation-rooted
 * variant that instead breaks a multi-segment constructor chain one segment per line, and the expression-lambda-body
 * packer that reuses the same machinery after a {@code ->}. The boundary exists so the chain printer's main decision
 * tree can defer to a single packed builder instead of carrying the greedy split-point loop and its object-root special
 * cases inline.
 *
 * <p>Callers still own chain root collection and compact segment text (supplied as handles that stay in the chain
 * printer because other layouts share them), the enclosing lambda-call suffix, and the fallback chain shapes this helper
 * re-enters through back-edges ({@link #objectRootSingleSegmentChain}, {@link #forcedMethodCallChain}) when a chain is
 * not packable.
 */
final class PackedMethodCallChainLayout {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Doc, Doc> chainContinuation;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final Function<MethodCallExpr, MethodCallChainSourcePlanner.MethodCallChainAnalysis> methodCallChainAnalysis;

    private final Predicate<MethodCallExpr> rootIsObjectCreation;

    private final BiFunction<MethodCallExpr, List<String>, Optional<String>> compactMethodCallChainRoot;

    private final Predicate<MethodCallExpr> compactMethodCallChainSegmentCanStayFlat;

    private final ObjectRootSingleSegmentChain objectRootSingleSegmentChain;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChain;

    PackedMethodCallChainLayout(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            SourceShapePolicy sourceShapePolicy,
            Function<Doc, Doc> chainContinuation,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            Function<MethodCallExpr, MethodCallChainSourcePlanner.MethodCallChainAnalysis> methodCallChainAnalysis,
            Predicate<MethodCallExpr> rootIsObjectCreation,
            BiFunction<MethodCallExpr, List<String>, Optional<String>> compactMethodCallChainRoot,
            Predicate<MethodCallExpr> compactMethodCallChainSegmentCanStayFlat,
            ObjectRootSingleSegmentChain objectRootSingleSegmentChain,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChain
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.sourceShapePolicy = sourceShapePolicy;
        this.chainContinuation = chainContinuation;
        this.objectCreationPrefix = objectCreationPrefix;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.methodCallChainAnalysis = methodCallChainAnalysis;
        this.rootIsObjectCreation = rootIsObjectCreation;
        this.compactMethodCallChainRoot = compactMethodCallChainRoot;
        this.compactMethodCallChainSegmentCanStayFlat = compactMethodCallChainSegmentCanStayFlat;
        this.objectRootSingleSegmentChain = objectRootSingleSegmentChain;
        this.forcedMethodCallChain = forcedMethodCallChain;
    }

    Optional<Doc> packedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        // Multi-segment object-creation-rooted chains (new X(...).a().b()...) break one segment per line: constructor
        // root alone, every .call() on its own continuation line. They skip the greedy packer, which would cram the root
        // plus the leading calls onto the first line (the lopsided shape this fix removes), and go straight to the
        // broken-object-root layout below. Name-rooted and factory-rooted chains, and single-segment object roots, still
        // greedy-pack here so their existing layouts are unchanged.
        if (!isGreedyPackedMultiSegmentObjectRoot(expression)) {
            Optional<Doc> packed = packedCompactMethodCallChain(
                expression,
                firstLineWidth,
                layoutWidth::continuationStatement,
                true
            ).map(this::packedMethodCallChainDoc);
            if (packed.isPresent()) {
                return packed;
            }
        }
        return packedBrokenObjectRootChain(expression, firstLineWidth);
    }

    /**
     * Identifies an object-creation-rooted chain with two or more {@code .call()} segments. Only these were greedy-packed
     * onto the first line; single-segment object roots ({@code new X(...).onlyCall(...)}) keep their existing layout.
     */
    private boolean isGreedyPackedMultiSegmentObjectRoot(MethodCallExpr expression) {
        if (!rootIsObjectCreation.test(expression)) {
            return false;
        }
        return methodCallChainAnalysis.apply(expression).calls().size() >= 2;
    }

    private Optional<PackedMethodCallChainText> packedCompactMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            ToIntFunction<String> continuationWidth,
            boolean reserveFinalTerminator
    ) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<String> segments = new ArrayList<>();
        Optional<String> root = compactMethodCallChainRoot.apply(expression, segments);
        if (root.isEmpty() || segments.isEmpty()) {
            return Optional.empty();
        }
        String firstLine = root.orElseThrow();
        if (firstLineWidth.applyAsInt(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        int splitIndex = 0;
        for (int index = 0; index < segments.size(); index++) {
            String candidate = firstLine + segments.get(index);
            String widthText = packedChainWidthText(candidate, index + 1 == segments.size(), reserveFinalTerminator);
            if (firstLineWidth.applyAsInt(widthText) > options.lineWidth()) {
                break;
            }
            firstLine = candidate;
            splitIndex = index + 1;
        }
        if (splitIndex >= segments.size()) {
            return Optional.empty();
        }
        List<String> remaining = segments.subList(splitIndex, segments.size());
        for (int index = 0; index < remaining.size(); index++) {
            boolean finalSegment = splitIndex + index + 1 == segments.size();
            String widthText = packedChainWidthText(remaining.get(index), finalSegment, reserveFinalTerminator);
            if (continuationWidth.applyAsInt(widthText) > options.lineWidth()) {
                return Optional.empty();
            }
        }
        return Optional.of(new PackedMethodCallChainText(firstLine, remaining));
    }

    private String packedChainWidthText(String text, boolean finalSegment, boolean reserveFinalTerminator) {
        return finalSegment && reserveFinalTerminator ? text + ";" : text;
    }

    private Doc packedMethodCallChainDoc(PackedMethodCallChainText chain) {
        return Doc.concat(
            Doc.text(chain.firstLine()),
            chainContinuation.apply(Doc.join(Doc.HARD_LINE, chain.remainingSegments().stream().map(Doc::text).toList()))
        );
    }

    /**
     * Renders an object-creation-rooted chain that must break, with the {@code new X(...)} root alone on the first line
     * and every {@code .call()} on its own indented continuation line.
     *
     * <p>This matches name-rooted chains (and prettier-java / google-java-format constructor roots), which already break
     * one segment per line. The earlier greedy-pack path crammed the root and the first calls onto the first line, an
     * inconsistent lopsided shape; for constructor roots that path is skipped so this layout owns them.
     *
     * <p>The multi-segment branch only needs the root opener to fit and the segments to stay flat. The unconditional
     * single-segment case ({@code new X(...).onlyCall(...)}) is intentionally left to {@link #objectRootSingleSegmentChain},
     * which keeps the call attached to the constructor close exactly like the name-rooted single-segment equivalent; its
     * narrower guards (non-empty constructor arguments, a constructor that does not already fit on one line) stay
     * unchanged so that single-segment routing is preserved.
     */
    private Optional<Doc> packedBrokenObjectRootChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis.apply(expression);
        if (
            analysis.hasComments()
            || analysis.hasBlockLambdaArgument()
            || !(analysis.root() instanceof ObjectCreationExpr objectCreation)
            || analysis.calls().isEmpty()
            || objectCreation.getAnonymousClassBody().isPresent()
            // The constructor root breaks its argument list by WIDTH — the `firstLineWidth`/`fitsOnOneLine` gates below
            // and `widthDrivenObjectCreation` — not by the author's line breaks, so a source-multiline root takes the
            // same path as any other and cannot oscillate.
            || analysis.calls().stream().anyMatch(call -> !compactMethodCallChainSegmentCanStayFlat.test(call))
            || firstLineWidth.applyAsInt(objectCreationPrefix.apply(objectCreation) + "(") > options.lineWidth()
        ) {
            return Optional.empty();
        }
        List<MethodCallExpr> calls = analysis.calls();
        if (calls.size() == 1) {
            if (objectCreation.getArguments().isEmpty() || sourceShapePolicy.fitsOnOneLine(objectCreation, firstLineWidth)) {
                return Optional.empty();
            }
            Doc rootDoc = brokenObjectCreationRenderer.apply(objectCreation);
            // This packed entry has no same-line leading prefix threaded, so pass root(): the leftEdgePrefix-gated
            // compact-tail fan-out in objectRootSingleSegmentChain is a return-chain-only refinement, so this
            // broken-object-creation path is unaffected.
            return Optional.of(objectRootSingleSegmentChain.render(
                objectCreation,
                rootDoc,
                calls.getFirst(),
                MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION,
                layoutWidth::currentIndented,
                firstLineWidth,
                LayoutContext.root()
            ));
        }
        // Multi-segment constructor chains break one segment per line through the shared chain machinery, which decides
        // whether the constructor root stays compact or breaks its own argument list, then lays every .call() on its own
        // continuation line. This is the same path name-rooted chains use, so the root-alone one-per-line shape and all
        // its comment/width handling stay consistent across root kinds.
        return forcedMethodCallChain.apply(expression, firstLineWidth);
    }

    private record PackedMethodCallChainText(String firstLine, List<String> remainingSegments) {
        PackedMethodCallChainText {
            remainingSegments = List.copyOf(remainingSegments);
        }
    }

    /**
     * Builds the chain fragment used when an expression-lambda body is packed after {@code ->}.
     *
     * <p>The lambda helper still owns the enclosing call suffix, but chain root collection, compact segment text, and
     * split-point selection stay with the method-call chain printer.
     */
    Optional<Doc> packedExpressionLambdaBodyChain(String firstLine, MethodCallExpr expression) {
        return packedExpressionLambdaBodyChain(firstLine, expression, this::expressionLambdaBodyLineWidth);
    }

    private Optional<Doc> packedExpressionLambdaBodyChain(
            String firstLine,
            MethodCallExpr expression,
            ToIntFunction<String> bodyLineWidth
    ) {
        return packedCompactMethodCallChain(
            expression,
            text -> bodyLineWidth.applyAsInt(firstLine + " " + text),
            this::packedExpressionLambdaBodyLineWidth,
            false
        ).map(this::packedMethodCallChainDoc);
    }

    private int expressionLambdaBodyLineWidth(String line) {
        return layoutWidth.blockStatement(options.indentUnit() + line);
    }

    private int packedExpressionLambdaBodyLineWidth(String line) {
        return layoutWidth.blockStatement(options.indentUnit().repeat(3) + line);
    }

    /**
     * Re-enters the chain printer's object-root single-segment layout. The tail is always the empty suffix for the packed
     * entry, so the caller binds {@code MethodCallChainTail.EMPTY} and this back-edge omits it.
     */
    @FunctionalInterface
    interface ObjectRootSingleSegmentChain {
        Doc render(
                ObjectCreationExpr objectCreation,
                Doc rootDoc,
                MethodCallExpr call,
                MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
                ToIntFunction<String> lineWidth,
                ToIntFunction<String> firstLineWidth,
                LayoutContext layout
        );
    }
}
