package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Owns the hugged lambda-body method-call-chain fan for {@link ExpressionLambdaArgumentLayout}.
 *
 * <p>This helper hosts the width-analysis predicates that answer "does this lambda-body method-call chain overflow at its
 * real rendered column, so it must fan onto dotted continuation lines while hugging the lambda header?" — the bare-call
 * root ({@link #overflowingHuggedBareRootChainBody}) and object-creation root
 * ({@link #overflowingHuggedObjectCreationRootChainBody}) admission gates, the shared true-column overflow probe
 * ({@link #chainOverflowsHuggedColumn}), the width-safe-improvement check ({@link #huggedFanFits}), and the clean-chain /
 * chain-root classifiers those gates consult — together with the render that emits the fan the gates admit
 * ({@link #huggedLambdaBodyChain}: root hugging the {@code ->} line, each {@code .selector(...)} on its own continuation
 * line, the enclosing {@code )} dedented). The boundary exists so the argument layout's body-shape decision tree can
 * consult one fan authority — should this chain fan, does the fan fit, and what does the fan look like — instead of
 * carrying every hugged-chain column probe inline. Every gate measures at the same
 * {@link LayoutWidth#nodeIndentWidth}-based rendered column the render assumes, so a fan verdict and its render stay in
 * lockstep and the shape is a fixpoint across passes.
 *
 * <p>The helper claims no ownership of the surrounding lambda hug: it reports whether a chain body overflows and renders
 * the fan it is asked for, but never decides whether the lambda argument itself can be hugged, nor which body shape wins
 * when the chain does not fan. That stays with the caller, which threads these predicates into its body-shape ranking and
 * composes the fan render with its own canonical-fan and arrow-hug gates.
 */
final class LambdaBodyChainFanLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<MethodCallExpr, String> methodCallSelector;

    private final LayoutWidth layoutWidth;

    private final FormatterOptions options;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> huggedLambdaBodyChainRenderer;

    LambdaBodyChainFanLayout(
            SourceShapePolicy sourceShapePolicy,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<MethodCallExpr, String> methodCallSelector,
            LayoutWidth layoutWidth,
            FormatterOptions options,
            BiFunction<String, MethodCallExpr, Optional<Doc>> huggedLambdaBodyChainRenderer
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.methodCallSelector = methodCallSelector;
        this.layoutWidth = layoutWidth;
        this.options = options;
        this.huggedLambdaBodyChainRenderer = huggedLambdaBodyChainRenderer;
    }

    /**
     * Renders a lambda-body method-call chain fanned onto dotted continuation lines while it hugs the lambda header on the
     * first line ({@code call(handler -> assertThat(handler)}⏎{@code .extracting(...)}⏎{@code .containsOnly(...))}), then
     * dedents the enclosing call close to its own line.
     *
     * <p>The fan itself is built by {@code huggedLambdaBodyChainRenderer} — the shared method-chain printer, which threads
     * {@code firstLine + " "} as the chain's {@link LayoutContext#leftEdgePrefix()} so every width gate measures at the
     * real rendered column and re-derives the identical source-neutral fan across passes. Both the canonical fan
     * (which fans a fan-threshold chain even when it fits) and the over-width bare-root branch reuse this one shape so
     * the two triggers produce byte-identical layouts for the chains they share.
     *
     * <p>The close dedents to its own line at the opener's column, the same shape a broken argument list renders
     * ({@code foo(}⏎{@code arg}⏎{@code )}) and the packed lambda-body shapes' {@code PackedLambdaBody.CLOSING_ON_OWN_LINE}
     * produce. The fanned chain already carries its own continuation indent, so the {@code HARD_LINE} + close stay outside
     * any extra indent and land back at the enclosing statement's column; a lambda header opening several calls before the
     * break would stack their closes on this one dedented line.
     */
    Optional<Doc> huggedLambdaBodyChain(String firstLine, MethodCallExpr chainBody) {
        return huggedLambdaBodyChainRenderer.apply(firstLine, chainBody)
                .map(huggedChain -> Doc.concat(
                        Doc.text(firstLine + " "),
                        huggedChain,
                        Doc.HARD_LINE,
                        Doc.text(")")
                ));
    }

    /**
     * Reports whether a lambda body is a bare-call-rooted method-call chain whose flat form overflows the line at the
     * lambda header's <em>real rendered column</em>, so it should fan onto dotted continuation lines while hugging the
     * lambda header ({@code someCall(x -> assertThat(x)}\n{@code .extracting(...)}\n{@code .containsOnly(...))}).
     *
     * <p>The gate is deliberately narrow so it moves only clean bare-call-rooted chains that overflow and leaves every
     * other lambda-body shape to the existing opener-packing and greedy-pack paths:
     *
     * <ul>
     *   <li><strong>It is a chain.</strong> The receiver is itself a method call, so there is at least one {@code .call(...)}
     *   selector to fan below the root. A single-call body ({@code x -> foo.bar(arg)}) has a non-call receiver and is left
     *   to the opener-packing shapes that break only the argument list.</li>
     *   <li><strong>Its root is a bare call.</strong> The innermost receiver is an unscoped method call
     *   ({@code assertThat(x)}), which {@code packedMethodCallChainBody} cannot fan — its greedy packer requires a
     *   non-call scope to root the first line. Chains rooted at a name, field access, or type ({@code journalWriter.atInfo()},
     *   {@code Type.builder()}) are left to that packer's existing greedy-packed-root shape, so this branch does not steal
     *   them and reshape them one-per-line.</li>
     *   <li><strong>Every call in the chain stays flat</strong> ({@link #chainCallsCanStayFlat}). No call — root or
     *   selector — carries a lambda argument, a comment, or a source-multiline argument. This is the clean dot-fannable
     *   shape the task targets ({@code assertThat(x).extracting(...).containsOnly("v")}): each {@code .call(...)} renders on
     *   one continuation line. A chain whose root call opens a lambda/text-block/anonymous-class argument
     *   ({@code verify(() -> render(textBlock)).ok()}) is not this shape — the argument already forces its own multi-line
     *   layout — so it is left to the opener-packing path that breaks that argument rather than being reshaped into a
     *   dotted fan.</li>
     *   <li><strong>It overflows at its true column</strong> ({@link #chainOverflowsHuggedColumn}). Measured at the
     *   lambda's block/type nesting depth ({@link LayoutWidth#nodeIndentWidth}, which counts every enclosing type and
     *   block) plus the header prefix ({@code firstLine} up to {@code ->}) plus the compact chain text. Measuring at the
     *   true column — rather than the fixed shallow baseline the sibling body probes assume — keeps the fan decision
     *   idempotent: the compact chain text and its rendered column are identical whether the input arrived flat or already
     *   fanned, so a chain that fans on the first pass re-derives the same overflow and stays fanned instead of oscillating
     *   with the flat opener shape.</li>
     *   <li><strong>The fan is a width-safe improvement</strong> ({@link #huggedFanFits}). At least two dotted selectors,
     *   the root fits after the header, and every selector fits on its continuation line — otherwise the forced chain would
     *   render a shape no better than the opener-packing fallback.</li>
     * </ul>
     */
    boolean overflowingHuggedBareRootChainBody(
            String firstLine,
            MethodCallExpr methodCall,
            ToIntFunction<String> columnWidth
    ) {
        return methodCall.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
            && chainRootIsBareCall(methodCall)
            && chainCallsCanStayFlat(methodCall)
            && chainOverflowsHuggedColumn(firstLine, methodCall, columnWidth)
            && huggedFanFits(firstLine, methodCall, columnWidth);
    }

    boolean chainRootIsBareCall(MethodCallExpr methodCall) {
        Optional<Expression> scope = methodCall.getScope();
        if (scope.filter(MethodCallExpr.class::isInstance).isPresent()) {
            return chainRootIsBareCall((MethodCallExpr) scope.orElseThrow());
        }
        return scope.isEmpty();
    }

    /**
     * Reports whether a lambda-body method-call chain is rooted at an object creation and overflows the line at the lambda
     * header's real rendered column, so it should FAN onto dotted continuation lines
     * ({@code .map(tp -> new TopicPartitions().setTopicId(tp.getKey())}⏎{@code .setPartitions(...))}) rather than keep the
     * whole {@code new X().setA().setB()} chain packed on the arrow line and over-run it.
     *
     * <p>The object-creation-root analogue of {@link #overflowingHuggedBareRootChainBody}: same clean-chain
     * scope ({@link #chainCallsCanStayFlat} — no argument lambda/comment/source-multiline) and same real-column overflow probe
     * ({@link #chainOverflowsHuggedColumn}, threaded true segment column), keyed on an OBJECT-CREATION root with at least one
     * selector to fan. The outermost-call-has-arguments guard mirrors the sibling chain-selector gate
     * ({@code MethodCallChainPrinter#bodyIsObjectCreationRootedChain}): a chain ending in an EMPTY {@code .build()} has no
     * argument list to break, so it stays on its existing shape.
     */
    boolean overflowingHuggedObjectCreationRootChainBody(
            String firstLine,
            MethodCallExpr methodCall,
            ToIntFunction<String> columnWidth
    ) {
        return chainRootIsObjectCreation(methodCall)
            && methodCall.getScope().isPresent()
            && !methodCall.getArguments().isEmpty()
            && chainCallsCanStayFlat(methodCall)
            && chainOverflowsHuggedColumn(firstLine, methodCall, columnWidth);
    }

    private boolean chainRootIsObjectCreation(MethodCallExpr methodCall) {
        Optional<Expression> scope = methodCall.getScope();
        if (scope.filter(MethodCallExpr.class::isInstance).isPresent()) {
            return chainRootIsObjectCreation((MethodCallExpr) scope.orElseThrow());
        }
        return scope.filter(ObjectCreationExpr.class::isInstance).isPresent();
    }

    private boolean chainOverflowsHuggedColumn(
            String firstLine,
            MethodCallExpr methodCall,
            ToIntFunction<String> columnWidth
    ) {
        // Take the wider of the {@code nodeIndentWidth}-based rendered column and the threaded true segment column
        // ({@code columnWidth} over the header prefix plus the compact chain). Monotone: the fan can only fire for a
        // chain that overflows at its real fanned column, never relax for a shallow one.
        String chainLine = firstLine + " " + compact.apply(methodCall);
        return Math.max(
            layoutWidth.nodeIndentWidth(methodCall) + firstLine.length() + 1 + compact.apply(methodCall).length(),
            columnWidth.applyAsInt(chainLine)
        ) > options.lineWidth();
    }

    /**
     * Reports whether the dotted fan is a clean, width-safe improvement over the opener-packing shape: at least two dotted
     * selectors to fan (a single-selector chain {@code foo(...).get()} is a one-dot break, not a fan, and stays with the
     * opener-packing shapes), the bare-call root fits after the lambda header on the first line, and every selector fits on
     * its own continuation line.
     *
     * <p>Without this gate the branch would fire for any over-column chain and hand the forced chain printer shapes it
     * cannot fan usefully — a single-selector chain, or a chain whose root is too wide to hug — which the printer then
     * renders flat over width or with a broken root argument list, a regression against the opener-packing fallback. The
     * widths are measured at the chain's real rendered column ({@link LayoutWidth#nodeIndentWidth} plus the header prefix,
     * then the double continuation indent for selectors), so the decision matches the shape the forced chain actually emits
     * and re-derives identically whether the input arrived flat or already fanned.
     */
    private boolean huggedFanFits(String firstLine, MethodCallExpr methodCall, ToIntFunction<String> columnWidth) {
        // {@code columnWidth} (the true segment column) is threaded but not consulted here; every width gate below
        // measures at the {@code nodeIndentWidth}-based rendered column.
        MethodCallExpr root = bareCallRoot(methodCall);
        List<MethodCallExpr> segments = chainSegmentsAboveRoot(methodCall, root);
        if (segments.size() < 2) {
            return false;
        }
        int rootColumn = layoutWidth.nodeIndentWidth(methodCall) + firstLine.length() + 1;
        if (rootColumn + compact.apply(root).length() > options.lineWidth()) {
            return false;
        }
        int continuationColumn = layoutWidth.nodeIndentWidth(methodCall) + options.indentUnit().length() * 2;
        return segments.stream()
                .allMatch(segment -> continuationColumn + compactChainSegment(segment).length() <= options.lineWidth());
    }

    private MethodCallExpr bareCallRoot(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(scope -> bareCallRoot((MethodCallExpr) scope))
                .orElse(methodCall);
    }

    private List<MethodCallExpr> chainSegmentsAboveRoot(MethodCallExpr methodCall, MethodCallExpr root) {
        List<MethodCallExpr> segments = new ArrayList<>();
        for (MethodCallExpr call = methodCall; call != root; ) {
            segments.add(call);
            call = (MethodCallExpr) call.getScope().orElseThrow();
        }
        return segments;
    }

    private String compactChainSegment(MethodCallExpr segment) {
        return "." + methodCallSelector.apply(segment) + "(" + compactJoin.apply(segment.getArguments()) + ")";
    }

    /**
     * Reports whether every call in the chain (the bare-call root and each dotted selector) has arguments that render flat
     * on their own line — no lambda argument, no comment, and nothing the source already broke across lines. This mirrors
     * the chain printer's own {@code compactMethodCallChainSegmentCanStayFlat} gate for the packed-chain shape, so the
     * dotted-fan branch only claims the same clean chains that renderer would fan, and defers anything whose argument
     * forces a multi-line layout to the opener-packing path.
     */
    private boolean chainCallsCanStayFlat(MethodCallExpr methodCall) {
        if (!methodCallArgumentsStayFlat(methodCall)) {
            return false;
        }
        return methodCall.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(scope -> chainCallsCanStayFlat((MethodCallExpr) scope))
                .orElse(true);
    }

    private boolean methodCallArgumentsStayFlat(MethodCallExpr methodCall) {
        return methodCall.getArguments()
                .stream()
                .noneMatch(argument -> argument instanceof LambdaExpr
                        || sourceShapePolicy.hasContainedComments(argument));
    }
}
