package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocRenderer;
import dev.lanwen.frmtr.java.MethodCallChainPrinter.MethodCallChainTail;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders a chain selector whose sole argument is an expression lambda, plus the comment-carrying lambda-hug family.
 *
 * <p>This helper owns two cohesive shapes the ordinary segment renderer delegates to. First, the SOURCE-NEUTRAL
 * expression-lambda selector ({@code .map(entry -> body)}): a {@link Doc#conditionalGroup} of a flat arm and an
 * always-broken hug/fan arm, both pure functions of the AST, so the selector's rendered width is a fixpoint and no
 * enclosing {@code bestFitting}/attach decision flips across passes. Second, the comment-carrying hug family: hugging a
 * chain selector's comment-bearing expression-lambda opener ({@code .or(() -> body})} so only the comment-driven body
 * breaks, and keeping a whole fluent chain's head flat when the only reason it must break is a comment inside the final
 * call's lambda argument. The boundary exists so the segment renderer keeps its grammar and comment-claim traversal
 * while these lambda-arrow shapes live in one place instead of as extra branches inside it.
 *
 * <p>Callers still own chain root/segment collection, the segment renderer itself, and — critically — the comment-claim
 * ordering: the enclosing {@code comments.speculatively(...)} wrappers stay at the segment renderer's call sites so this
 * helper only ever renders under a claim the caller opened. The broken-segment fallback shape, the compact-segment-flat
 * predicate, the fanned-selector column probe, the final-segment-suffix appender, the segment prefix text, and the
 * object-creation-root test all stay with the caller and are injected as handles; this helper decides only flat-vs-hug
 * and how a comment-carrying lambda body is laid out once those collaborators have answered.
 */
final class ChainSelectorLambdaLayout {

    private final CommentTracker comments;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody;

    private final ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments huggableExpressionLambdaArguments;

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener expressionLambdaMethodCallBodyOpener;

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug;

    private final Function<MethodCallExpr, String> methodCallSegmentPrefixText;

    private final Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation;

    private final Predicate<MethodCallExpr> compactMethodCallChainSegmentCanStayFlat;

    private final BiFunction<Doc, MethodCallChainTail, Doc> appendFinalSegmentSuffix;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, ToIntFunction<String>> fannedSelectorColumnWidth;

    private final BrokenMethodCallSegment brokenMethodCallSegment;

    ChainSelectorLambdaLayout(
            CommentTracker comments,
            CompactSourceText compactSource,
            LayoutWidth layoutWidth,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            Function<LambdaExpr, String> lambdaParameters,
            Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody,
            ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments huggableExpressionLambdaArguments,
            ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener expressionLambdaMethodCallBodyOpener,
            ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug,
            Function<MethodCallExpr, String> methodCallSegmentPrefixText,
            Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation,
            Predicate<MethodCallExpr> compactMethodCallChainSegmentCanStayFlat,
            BiFunction<Doc, MethodCallChainTail, Doc> appendFinalSegmentSuffix,
            BiFunction<MethodCallExpr, ToIntFunction<String>, ToIntFunction<String>> fannedSelectorColumnWidth,
            BrokenMethodCallSegment brokenMethodCallSegment
    ) {
        this.comments = comments;
        this.compactSource = compactSource;
        this.layoutWidth = layoutWidth;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.lambdaParameters = lambdaParameters;
        this.huggedGapCommentedLambdaBody = huggedGapCommentedLambdaBody;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.expressionLambdaMethodCallBodyOpener = expressionLambdaMethodCallBodyOpener;
        this.expressionLambdaLogicalBinaryBodyOpenerHug = expressionLambdaLogicalBinaryBodyOpenerHug;
        this.methodCallSegmentPrefixText = methodCallSegmentPrefixText;
        this.methodCallChainRootIsObjectCreation = methodCallChainRootIsObjectCreation;
        this.compactMethodCallChainSegmentCanStayFlat = compactMethodCallChainSegmentCanStayFlat;
        this.appendFinalSegmentSuffix = appendFinalSegmentSuffix;
        this.fannedSelectorColumnWidth = fannedSelectorColumnWidth;
        this.brokenMethodCallSegment = brokenMethodCallSegment;
    }

    /**
     * Hugs a chain segment whose single argument is an expression lambda whose body carries comments, keeping the lambda
     * opener on the selector line ({@code .or(() -> body}) and collapsing the call's closing parenthesis onto the body's
     * last line ({@code …)}) instead of breaking the lambda onto its own indented line with a stacked closer.
     *
     * <p>This is the comment-carrying counterpart of {@link ExpressionLambdaArgumentLayout}'s comment-free chain-lambda
     * hug, which deliberately refuses any lambda with contained comments. The comment forces the body to break, so the
     * only remaining choice is whether the lambda header breaks with it. Hugging keeps the shape stable across re-formats:
     * the body is rendered through the ordinary expression/chain renderer (which preserves the body's chain-link comments
     * one-per-line and recurses into nested {@code .map(ref -> …)} segments so they hug too), and the closing parenthesis
     * attaches to whatever the body renderer already ends with. A nested broken call ends with its own {@code )}, so the
     * appended {@code )} collapses to {@code …))} — exactly the shape that re-parses to the same comment attachment, which
     * is what makes the collapsed closer idempotent.
     *
     * <p>It is scoped to bodies/inner-lambdas that actually carry comments (the comment-free case stays with the existing
     * width-driven hug) and to clean lambda parameters, so the parameter text and {@code ->} can live on the selector
     * line verbatim. Object-creation bodies are excluded because an anonymous class body has no place in this opener
     * shape; they keep the broken-segment fallback.
     */
    Optional<Doc> huggedCommentCarryingExpressionLambdaSegment(
            String prefix,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (
            expression.getArguments().size() != 1
            || !(expression.getArgument(0) instanceof LambdaExpr lambdaExpr)
        ) {
            return Optional.empty();
        }
        Optional<String> header = commentCarryingLambdaHugHeader(lambdaExpr);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Optional<Doc> tail = huggedCommentLambdaTail(lambdaExpr);
        if (tail.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "(" + header.orElseThrow()),
                tail.orElseThrow(),
                Doc.text(")" + finalSegmentSuffix)
            )
        );
    }

    /**
     * Renders everything after a hugged comment-carrying lambda's opener: the gap comment(s) plus body, or — when the body
     * is itself a flat-headable comment-lambda chain — that nested hug, ready to follow the reconstructed {@code params ->}
     * header and precede the collapsing closing parenthesis. Empty when the lambda is not a hug candidate.
     *
     * <p>The innermost lambda's {@code ->}-to-body gap comments (the {@code parcel -> // note merge(...)} shape) are
     * rendered on their own indented lines before the body so they survive the hug. When the innermost body is instead a
     * fluent chain whose scope links carry no inter-link comment and whose final call is another comment-carrying
     * huggable lambda (the {@code route.parcels().forEach(parcel -> …)} shape), its scope links pack flat and the nested
     * lambda hugs in turn, so the whole head stays on one line and only the comment-driven body breaks. An inter-link
     * comment between scope links (the {@code #94} {@code Optional.of(x) // note .map(y)} shape) instead falls through to
     * the ordinary chain renderer, which lays the scope one-per-line with that comment preserved.
     */
    private Optional<Doc> huggedCommentLambdaTail(LambdaExpr lambdaExpr) {
        LambdaExpr innermost = lambdaExpr;
        while (innermost.getExpressionBody().filter(LambdaExpr.class::isInstance).isPresent()) {
            innermost = (LambdaExpr) innermost.getExpressionBody().orElseThrow();
        }
        Optional<Doc> gapBody = huggedGapCommentedLambdaBody.apply(innermost);
        if (gapBody.isPresent()) {
            return gapBody;
        }
        Expression body = innermost.getExpressionBody().orElseThrow();
        if (body instanceof MethodCallExpr methodCall) {
            Optional<Doc> flatHeaded = flatHeadedHuggedCommentLambdaChain(methodCall);
            if (flatHeaded.isPresent()) {
                return Optional.of(Doc.concat(Doc.text(" "), flatHeaded.orElseThrow()));
            }
        }
        return Optional.of(Doc.concat(Doc.text(" "), expressionRenderer.format(body, LayoutContext.root())));
    }

    /**
     * Packs a chain's scope links flat and hugs its final comment-carrying lambda, or empty when the chain is not a
     * flat-headed hug candidate (no final comment-lambda, an inter-link comment in the scope, a non-flat scope link, or a
     * head that overflows).
     */
    private Optional<Doc> flatHeadedHuggedCommentLambdaChain(MethodCallExpr methodCall) {
        if (
            methodCall.getArguments().size() != 1
            || !(methodCall.getArgument(0) instanceof LambdaExpr lambdaExpr)
        ) {
            return Optional.empty();
        }
        Optional<String> header = commentCarryingLambdaHugHeader(lambdaExpr);
        if (
            header.isEmpty()
            || methodCall.getScope().isEmpty()
            || methodCallHasCommentOutsideLambdaArgument(methodCall, lambdaExpr)
        ) {
            return Optional.empty();
        }
        Optional<String> flatScope = flatChainScopeText(methodCall.getScope().orElseThrow());
        if (flatScope.isEmpty()) {
            return Optional.empty();
        }
        String opener = flatScope.orElseThrow()
            + "."
            + methodCallSegmentPrefixText.apply(methodCall).substring(1)
            + "(" + header.orElseThrow();
        if (layoutWidth.currentIndented(opener) > options.lineWidth()) {
            return Optional.empty();
        }
        Optional<Doc> tail = huggedCommentLambdaTail(lambdaExpr);
        if (tail.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(opener),
                tail.orElseThrow(),
                nestedChainHugClosing(tail.orElseThrow())
            )
        );
    }

    /**
     * Places the closing parenthesis of a NESTED comment-carrying lambda-chain hug (the inner
     * {@code route.parcels().forEach(parcel -> …)} of a {@code manifest.routes().forEach(route -> …)} fan). When the hugged
     * body already breaks across lines — a gap comment, a broken body call, or a deeper nested hug — the enclosing call's
     * {@code )} dedents onto its own line so it aligns under the fanned selector rather than gluing at the body's inner
     * indent ({@code merge(…}⏎{@code )}⏎{@code ))} instead of {@code merge(…}⏎{@code )))}), matching PR #279 review. The
     * outermost {@link #huggedCommentCarryingExpressionLambdaSegment} closer then glues to this dedented line, so the whole
     * run of enclosing closers lands together under the selector. A body that renders on a single line (nothing forced it
     * to break) keeps the collapsed {@code …)} shape. The dedented closer re-parses to the same chain and comment
     * attachment, so the layout stays idempotent.
     */
    private Doc nestedChainHugClosing(Doc tail) {
        return DocRenderer.containsHardLine(tail)
            ? Doc.concat(Doc.HARD_LINE, Doc.text(")"))
            : Doc.text(")");
    }

    /**
     * Keeps an entire fluent chain's head flat when the only reason it must break is a comment carried inside the final
     * call's expression-lambda argument — the {@code manifest.routes().forEach(route -> … )} shape. The head links pack
     * onto the opener line, the final lambda hugs, and the comment-driven body is the only thing that breaks.
     *
     * <p>This is scoped to chains whose comments all live inside that final lambda (an inter-link comment between head
     * links would instead force the one-per-line layout) and whose flat head fits the line, so a comment-free or
     * width-driven chain keeps its existing one-per-line layout. The flat head plus the hugged lambda re-parses to the
     * same chain and the same comment attachment, so the layout is idempotent.
     */
    Optional<Doc> flatHeadHuggedCommentLambdaChain(
            MethodCallExpr expression,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (
            analysis.calls().isEmpty()
            || analysis.rootHasComments()
        ) {
            return Optional.empty();
        }
        if (
            expression.getArguments().size() != 1
            || !(expression.getArgument(0) instanceof LambdaExpr lambdaExpr)
            || chainHasCommentOutsideFinalLambda(expression, lambdaExpr)
        ) {
            return Optional.empty();
        }
        return flatHeadedHuggedCommentLambdaChain(expression)
                .map(body -> appendFinalSegmentSuffix.apply(body, finalSegmentSuffix));
    }

    /**
     * Reports whether the chain rooted at {@code expression} carries any comment that is not nested inside the final
     * call's lambda argument. Used to keep the flat-head hug scoped to purely tail-comment-driven chains.
     */
    private boolean chainHasCommentOutsideFinalLambda(MethodCallExpr expression, LambdaExpr finalLambda) {
        return expression.getAllContainedComments()
                .stream()
                .anyMatch(comment -> finalLambda.getAllContainedComments().stream().noneMatch(inside -> inside == comment));
    }

    /**
     * Reports whether a call carries a comment that is not nested inside its hugged lambda argument — a chain-link comment
     * (the {@code Optional.of(x) // note .map(y)} name comment) that must force a one-per-line break rather than a flat
     * head. Such a comment lives in the call's contained set but not in the lambda's, so flat-packing this link would drop
     * or misplace it.
     */
    private boolean methodCallHasCommentOutsideLambdaArgument(MethodCallExpr methodCall, LambdaExpr lambdaExpr) {
        return methodCall.getAllContainedComments()
                .stream()
                .anyMatch(comment -> lambdaExpr.getAllContainedComments().stream().noneMatch(inside -> inside == comment));
    }

    /**
     * Returns the compact one-line text for a chain scope when every link can stay flat and carries no comment, or empty
     * otherwise. This is the flat-head portion that precedes a hugged comment-carrying lambda's final selector.
     */
    private Optional<String> flatChainScopeText(Expression scope) {
        if (!scope.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (scope instanceof MethodCallExpr methodCall) {
            if (!compactMethodCallChainSegmentCanStayFlat.test(methodCall) || methodCall.getScope().isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(compactSource.compact(scope));
    }

    /**
     * Builds the hug header ({@code params ->}, chained through nested expression lambdas as {@code params -> inner ->})
     * for a comment-carrying expression-lambda argument, or empty when the lambda is not eligible to hug.
     *
     * <p>Eligibility mirrors the comment-free hug but inverted on comments: the lambda (or one of its nested lambdas) must
     * carry a contained comment, the parameters must be comment-free so the header text is reconstructable verbatim, and
     * the innermost body must be a method call (a chain or call whose comments the expression renderer can preserve while
     * the appended closing parenthesis collapses). Boundary, parameter, and gap comments around the lambda itself are
     * left to the dedicated comment-preserving renderers, so they exclude this hug.
     */
    private Optional<String> commentCarryingLambdaHugHeader(LambdaExpr lambdaExpr) {
        if (
            lambdaExpr.getExpressionBody().isEmpty()
            || lambdaExpr.getAllContainedComments().isEmpty()
            || !lambdaParameterHugCandidate(lambdaExpr)
        ) {
            return Optional.empty();
        }
        StringBuilder header = new StringBuilder(lambdaParameters.apply(lambdaExpr) + " ->");
        Expression body = lambdaExpr.getExpressionBody().orElseThrow();
        while (body instanceof LambdaExpr nested) {
            if (nested.getExpressionBody().isEmpty() || !lambdaParameterHugCandidate(nested)) {
                return Optional.empty();
            }
            header.append(' ').append(lambdaParameters.apply(nested)).append(" ->");
            body = nested.getExpressionBody().orElseThrow();
        }
        if (!(body instanceof MethodCallExpr)) {
            return Optional.empty();
        }
        return Optional.of(header.toString());
    }

    private boolean lambdaParameterHugCandidate(LambdaExpr lambdaExpr) {
        return lambdaExpr.getParameters().stream().allMatch(parameter -> parameter.getAllContainedComments().isEmpty());
    }

    /**
     * Canonical-fan cutover seam (End-state A): renders a chain selector whose sole trailing argument is an expression
     * lambda ({@code .map(entry -> body)}) as a SOURCE-NEUTRAL {@link Doc#conditionalGroup} of two pure-AST arms, so the
     * {@code DocRenderer} picks flat-vs-hug at the true live column. Returns empty
     * (the segment falls through to the generic argument-group path) when the selector is not this clean single-trailing-
     * expression-lambda shape — a leading-argument, multiple-lambda, block-lambda, or comment-carrying selector, or a
     * lambda whose parameters must break, all of which either reach here already handled by an earlier branch or want the
     * unchanged generic layout.
     *
     * <p>The arms are ordered flattest-first, matching the {@code conditionalGroup} contract (first flat fit wins, the last
     * is the unconditional broken fallback):
     * <ul>
     *   <li><b>Flat</b> ({@code .name(params -> compactBody)}): the whole selector on one line, the lambda body compacted.
     *       Chosen whenever it fits at the render column.</li>
     *   <li><b>Hug/broken</b>: the always-valid fallback (it carries forced breaks and so never "fits flat"). For a
     *       method-call body it is the shared expression-lambda hug/fan (the U7 canonical fan, the over-width
     *       {@code overflowingHuggedBareRootChainBody} hug, the {@code methodCallBodyWithOpener} opener hug); for any other
     *       body it is {@link #brokenMethodCallSegment} — the selector's own argument list breaks — matching the
     *       single-segment {@code compactRootWithBrokenFinalSegment} tail so the two paths converge on identical bytes.</li>
     * </ul>
     * Both arms are pure functions of the AST, so the selector's rendered width is a fixpoint and any enclosing
     * {@code bestFitting}/attach decision does not flip across passes.
     */
    Optional<Doc> sourceNeutralExpressionLambdaSegment(
            String prefix,
            MethodCallExpr expression,
            Doc segmentPrefix,
            MethodCallChainTail finalSegmentSuffix,
            boolean segmentOnOwnLine,
            ToIntFunction<String> compactSegmentWidth
    ) {
        Optional<LambdaExpr> lambda = soleTrailingExpressionLambdaSelectorArgument(expression);
        if (lambda.isEmpty()) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = lambda.orElseThrow();
        if (
            !lambdaExpr.getAllContainedComments().isEmpty()
            || lambdaParametersShouldBreakInSegment(lambdaExpr)
        ) {
            return Optional.empty();
        }
        Expression body = lambdaExpr.getExpressionBody().orElseThrow();
        // Build the flat selector text from a SOURCE-NEUTRAL compact of the lambda. {@code compactSource.compactJoin} has no
        // {@code LambdaExpr} case, so it falls to {@code compactTokenText}, which only collapses whitespace RUNS — leaving a
        // stray {@code " ."} where the source wrapped a body chain before a selector ({@code assertThat(x) .isPresent()}). That
        // spelling is source-shaped (it appears only where the source wrapped the body across lines) and would flip the
        // flat arm across passes. The
        // lambda body compacts cleanly through {@code compactSource.compact} (its {@code MethodCallExpr}/etc. cases reconstruct
        // canonical dot spacing), so the flat selector is reassembled here as {@code prefix(params -> compactBody)}.
        String flatLambda = lambdaParameters.apply(lambdaExpr) + " -> " + compactSource.compact(body);
        Doc flatBody = Doc.text(prefix + "(" + flatLambda + ")" + finalSegmentSuffix);
        // The broken/hug arm.
        //
        // A METHOD-CALL lambda body whose chain root is NOT an object creation ({@code entry -> meshCatalog.prepare(…)},
        // {@code outcome -> journalWriter.atInfo()….log(…)}) is the chain / fluent-builder / opener family this cutover
        // targets: it renders through the shared expression-lambda hug/fan renderer ({@code huggableExpressionLambdaArguments}
        // = ExpressionLambdaArgumentLayout#huggableMethodCallArguments), reproducing every established shape (the U7
        // canonical fan, the over-width
        // {@code overflowingHuggedBareRootChainBody} hug, the {@code methodCallBodyWithOpener} opener hug, the packed body).
        // If that shared renderer withholds this body (returns empty — a nested-lambda body, a body its {@code plan} gate
        // rejects), THIS SEAM YIELDS ({@link Optional#empty()}) so the selector keeps the unchanged generic layout rather
        // than being force-rendered through {@link #brokenMethodCallSegment}, whose standalone-lambda rendering path does not
        // handle every such body (it throws on a nested-lambda body — the {@code entry -> entry.cause().ifPresent(c -> …)}
        // shape). Yielding is safe: those bodies were never the oscillating family this cutover set out to fix.
        //
        // Every OTHER body — a NON-method-call body (object creation, binary, ternary…), or a method-call body whose chain
        // root is an OBJECT CREATION ({@code x -> new WorkerLoad.Builder(k).with(…).build()}) — uses {@link #brokenMethodCallSegment}
        // (the selector's own argument list breaks). Two reasons converge on it: (1) it is exactly the shape the single-segment
        // {@link #compactRootWithBrokenFinalSegment} tail produces for the same selector ({@code .orElseThrow(() -> new X(…))}),
        // so a selector rendered here and one rendered by that sibling path converge on identical bytes rather than flipping
        // opener-hug ⇄ broken-segment; and (2) an object-creation-rooted chain body is the deferred nested-root slice of this
        // cutover — its hug renders {@code new X()} at column zero and oscillates {@code new X().setA(} ⇄ {@code new X()}⏎{@code .setA(}
        // (the same reason {@link #lambdaBodyChainFansByCanonicalRule} withholds object-creation roots), so it is kept on the
        // stable broken-segment shape. All these {@code brokenMethodCallSegment} bodies are object-creation / binary / plain
        // expressions the segment argument-list break renders safely.
        Doc hugBody;
        if (body instanceof MethodCallExpr bodyChain && !methodCallChainRootIsObjectCreation.test(bodyChain)) {
            // A FANNED selector whose lambda body is a CHAIN or a call carrying its own argument-lambda (i.e. NOT a
            // single-call-safe body) measures its hug at the selector's TRUE continuation column, not the fixed
            // CONTINUATION budget the fan threads. That budget under-counts the real column by an indent level for any
            // chain nested one type/block below a top-level statement (a {@code return x.map(p -> body).orElse(…)} chain
            // in a method body renders {@code .map(p -> …)} at {@code 16 + len} but the budget reads {@code 12 + len}), so
            // a body whose flat selector overflows the real column yet fits the under-counted budget reads as "fits", the
            // shared renderer withholds the hug, and the selector breaks its argument list / drops the lambda arrow onto
            // its own line (PR #279 review, expression-lambda argument-opener cluster — {@code .flatMap(record -> …)} and
            // {@code .map(plan -> plan.firstLineFits(…))}). Single-call-safe bodies keep the fixed budget so their
            // established opener-hug shapes ({@code .forEach((tp, pd) -> add(…))}) do not churn.
            ToIntFunction<String> hugColumnWidth =
                segmentOnOwnLine && !bodyIsSingleCallSafeForBrokenSegment(bodyChain)
                    ? fannedSelectorColumnWidth.apply(expression, compactSegmentWidth)
                    : compactSegmentWidth;
            Optional<Doc> hug = comments.speculatively(
                () -> huggableExpressionLambdaArguments.render(
                    prefix,
                    expression.getArguments(),
                    hugColumnWidth
                )
            );
            // The hug is only a valid conditionalGroup FALLBACK when it is a genuinely broken layout. The shared
            // huggableExpressionLambdaArguments renderer can hand back a FLAT one-liner for a short single-call body: its
            // {@code compactBodyWithClosingLine} branch returns the compact {@code body)} on one line whenever that line
            // fits its measured column budget. A flat hug is redundant with {@code flatBody} and invalid as the always-broken
            // fallback the conditionalGroup renders when {@code flatBody} overflows: as the fallback arm it would render an
            // over-wide selector flat (the {@code ReplicaVerificationTool} class of oscillation).
            // When the hug carries no forced break (the degenerate flat case, redundant with {@code flatBody}) AND the lambda
            // body is a single call the broken segment can safely re-render, delegate to
            // {@link #singleCallBodyOpenerHugOrBrokenSegment}: review round 2 (comment #3) builds the source-neutral opener
            // hug ({@code .forEach((tp, partitionData) -> replicaBuffer.addFetchedData(}⏎…⏎{@code ))}) directly for such a
            // fanned selector, and falls back to {@link #brokenMethodCallSegment} — the same source-neutral broken shape the
            // collapsed-source pass reaches — when the opener hug is unavailable, so both passes converge and the fallback
            // always carries a forced break as the conditionalGroup contract requires.
            //
            // The substitution is scoped to a SINGLE-CALL body ({@code entry -> replicaBuffer.addFetchedData(a, b, c)}, its
            // receiver a plain name/field, not another call) — see {@link #bodyIsSingleCallSafeForBrokenSegment}. A CHAIN
            // body ({@code node -> assertThat(node.decision()).isPresent()}) is withheld: {@code brokenMethodCallSegment}
            // re-renders the selector's lambda argument through the generic argument list, whose standalone lambda-body layout
            // ({@code ExpressionLambdaMethodCallBodyLayout.scopedCallBodyWithHeader}) dereferences the body's inner scope-call
            // scope and throws when that scope-call is an unqualified static call ({@code assertThat(...)}). Such a chain-body
            // hug does not oscillate at a shallow column — it is format-twice stable there — so keeping its hug is safe. A
            // hug that DOES carry a forced break (the fan / over-width hug / opener hug for a
            // fan-threshold or overflowing chain body) is a real broken layout and is kept unchanged, preserving those
            // established shapes. Yielding empty when the renderer withholds the body entirely is preserved.
            // Unified opener admission. When the shared renderer WITHHOLDS this single-call body
            // ({@code plan} bailed because its fixed-budget flat probe read the body as fitting at a shallow column, blind to
            // the selector's real fanned continuation column — the {@code .forEach((tp, pd) -> replicaBuffer.addFetchedData(…))}
            // over-width family), do NOT yield the whole seam to the generic argument-list path (which breaks {@code .forEach(}
            // onto its own line and then oscillates flat⇄broken across passes). Instead build the source-neutral opener-hug
            // arm DIRECTLY through {@link #singleCallBodyOpenerHugOrBrokenSegment} and let the enclosing {@code conditionalGroup}
            // decide flat-vs-broken at the true live column: {@code flatBody} wins when the flat selector fits, the always-broken
            // opener hug is the fallback when it overflows. Scoped to a single-call-safe, fanned selector so it never reaches a
            // nested-lambda / chain body the direct opener hug cannot render (those still yield). This is the SAME direct opener
            // the standalone lambda-body path routes through, so both agree on the width verdict at the same column.
            if (hug.isEmpty()) {
                if (
                    !segmentOnOwnLine
                    || !bodyIsSingleCallSafeForBrokenSegment(bodyChain)
                    || !bodyOpenerHugArgumentsRenderFlatSafely(bodyChain)
                ) {
                    return Optional.empty();
                }
                Optional<Doc> directOpener = comments.speculatively(
                    () -> singleCallLambdaBodyOpenerHug(prefix, lambdaExpr, bodyChain, finalSegmentSuffix, compactSegmentWidth)
                );
                if (directOpener.isEmpty()) {
                    return Optional.empty();
                }
                hugBody = directOpener.orElseThrow();
            } else {
                Doc hugDoc = hug.orElseThrow();
                hugBody = DocRenderer.containsHardLine(hugDoc) || !bodyIsSingleCallSafeForBrokenSegment(bodyChain)
                    ? Doc.concat(hugDoc, finalSegmentSuffix.doc())
                    : singleCallBodyOpenerHugOrBrokenSegment(
                        prefix,
                        expression,
                        lambdaExpr,
                        bodyChain,
                        finalSegmentSuffix,
                        segmentOnOwnLine,
                        compactSegmentWidth
                    );
            }
        } else {
            // Single expression-lambda argument hugs its call opener (gjf/prettier-java, comments #2/#3/#5/#6). Any NON-
            // method-call body — an OBJECT CREATION ({@code (left, right) -> new ImageCounter(…)}), an OBJECT-CREATION-ROOTED
            // chain ({@code listener -> new VotersEndpoint().setName(…).setHost(…)}), a TERNARY
            // ({@code ex -> index + 1 < managers.size() ? authenticate(…) : Mono.error(ex)}, review comment #2), or a LOGICAL
            // BINARY ({@code initializer -> initializer instanceof A || initializer instanceof B || …}) — keeps the lambda
            // opener glued to the selector rather than breaking the selector's own parenthesis onto a fresh line:
            // {@code .onErrorResume(ex -> index + 1 < managers.size()}⏎{@code ? authenticate(…)}⏎{@code : Mono.error(ex)}⏎{@code )}.
            // {@link #expressionBodyOpenerHug} broadens the round-1 object-creation-only hug to every body the shared
            // {@code huggableExpressionLambdaArguments} renderer hugs with a forced break, restoring the opener hug the
            // one-per-line fan over-broke.
            //
            // Scoped to a FANNED selector ({@code segmentOnOwnLine}): only a selector on its own dotted continuation line has
            // a STABLE column (the continuation indent), so the opener hug re-derives the identical shape across passes. A
            // selector rendered beside a preceding token — a single-selector initializer/return tail such as
            // {@code spanFor(x).orElseThrow(() -> new IllegalArgumentException(…))} — sits at a variable column, and hugging
            // its opener there flips opener-hug ⇄ broken-parenthesis against the initializer's own layout across passes (the
            // {@code ledgerSpan} oscillation). Such a tail keeps the {@link #brokenMethodCallSegment} shape, which is exactly
            // what the single-segment {@link #compactRootWithBrokenFinalSegment} tail produces, so the two paths converge.
            // The shared renderer's opener/broken-object/broken-ternary shapes are used only when they carry a forced break (a
            // genuine broken layout the conditionalGroup fallback contract requires); its FLAT degenerate case (redundant with
            // {@code flatBody}) and any body it withholds fall through to {@link #brokenMethodCallSegment} too.
            Optional<Doc> openerHug = segmentOnOwnLine
                ? expressionBodyOpenerHug(prefix, expression, body, finalSegmentSuffix, compactSegmentWidth)
                : Optional.empty();
            hugBody = openerHug.orElseGet(
                () -> brokenMethodCallSegment.render(expression, prefix, Doc.EMPTY, finalSegmentSuffix)
            );
        }
        // A conditional group (NOT bestFitting) chooses flat-vs-hug purely by whether the flat selector fits at the live
        // column. This is deliberate: the fan-carrying selector nests inside the enclosing chain fan (itself a bestFitting on
        // the return/initializer seams), and a per-selector {@code bestFitting} would sit past the {@code MAX_BEST_FITTING_DEPTH}
        // linear-time bound (D16) — beyond which {@code chooseBestFitting} silently keeps the FIRST (flat) arm, flattening an
        // over-wide chain body onto one line. A conditional group is not depth-bounded: it flat-fit-probes {@code flatBody} and
        // renders the {@code hugBody} (which carries forced breaks and so never "fits flat") as the unconditional fallback when
        // {@code flatBody} overflows. The verdict is a pure function of the AST (compact flat width vs. the live column), so the
        // selector's shape is a fixpoint. {@code segmentPrefix} (any leading comments) is prepended ONCE outside the group so
        // the two arms do not share a sub-{@code Doc} instance whose bounded flat-fit probe could perturb the other arm.
        return Optional.of(Doc.concat(segmentPrefix, Doc.conditionalGroup(List.of(flatBody, hugBody))));
    }

    /**
     * Single expression-lambda argument hugs its call opener (gjf/prettier-java, comments #2/#5/#6): builds the
     * opener-hugged broken layout for a FANNED chain selector whose sole argument is an expression lambda whose body is a
     * SOURCE-NEUTRAL hug shape — an OBJECT CREATION ({@code .reduce((left, right) -> new ImageCounter(}⏎…), an
     * OBJECT-CREATION-ROOTED chain ({@code .map(listener -> new VotersEndpoint().setName(…).setHost(}⏎…), a TERNARY
     * ({@code .onErrorResume(ex -> cond}⏎{@code ? then}⏎{@code : else}⏎{@code )}, review comment #2), or a LOGICAL BINARY
     * ({@code .map(region -> region.beginOffset() == expected.beginOffset()}⏎{@code && region.endOffset() == …}⏎{@code )},
     * review round 3) — keeping {@code .selector(params -> body…} on the selector line rather than breaking the selector
     * parenthesis onto its own line.
     *
     * <p>Review round 2 broadened this from the round-1 object-creation-only hug to also cover TERNARY bodies, whose shared
     * hug ({@code packedConditionalBody}) is a pure width function of the AST. Review round 3 adds LOGICAL ({@code &&}/{@code ||})
     * BINARY bodies through the DIRECT source-neutral {@code expressionLambdaLogicalBinaryBodyOpenerHug} — NOT the shared
     * {@code huggableExpressionLambdaArguments} renderer, and NOT the {@code binaryMethodCallBodyWithOpener} path. The direct
     * helper renders the operands with a pure
     * {@code nestedLines} AST function and always dedents the close, so it is a fixpoint; a top-level RELATIONAL body
     * ({@code x -> f(...) == ALLOWED}) is not a logical binary, so the helper leaves it unclaimed and it keeps the
     * {@link #brokenMethodCallSegment} shape and stays idempotent. The object-creation/ternary hug is produced by the shared
     * {@code huggableExpressionLambdaArguments} renderer ({@link ExpressionLambdaArgumentLayout#huggableMethodCallArguments}).
     * Every hug is returned only when it carries a forced break ({@link DocRenderer#containsHardLine}): as the second arm of
     * the selector's {@link Doc#conditionalGroup} it must be a genuinely broken fallback, never a flat layout redundant with
     * the flat arm (which would let an over-wide selector render flat — the {@code ReplicaVerificationTool} class of
     * oscillation). Returns empty for any body neither the direct binary helper nor the shared renderer claims (or renders
     * flat), so the caller keeps the {@link #brokenMethodCallSegment} shape for it. The {@code finalSegmentSuffix} is appended
     * after the hug so a final selector still carries its statement terminator.
     *
     * <p>A PLAIN method-call body (root not an object creation) never reaches here — the caller's method-call branch routes
     * it through the shared renderer directly (with the {@link #singleCallBodyOpenerHugOrBrokenSegment} substitution for the
     * degenerate flat single-call case). Only the {@code else} branch's bodies reach here. An object-creation-rooted chain
     * whose OUTERMOST call is EMPTY ({@code new WorkerLoad.Builder(k).with(a, b).build()}) is withheld
     * ({@link #bodyIsObjectCreationRootedChain} false): its opener hug would force-break the empty {@code .build()} into a
     * malformed {@code .build(}⏎⏎{@code )} that oscillates, so it keeps the {@link #brokenMethodCallSegment} shape.
     */
    private Optional<Doc> expressionBodyOpenerHug(
            String prefix,
            MethodCallExpr expression,
            Expression body,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth
    ) {
        // A LOGICAL BINARY body ({@code region -> region.beginOffset() == expected.beginOffset() && …}, review round 3) hugs
        // its opener with the first operand on the selector line, each following {@code &&}/{@code ||} operand one per line
        // below, and the enclosing {@code )} dedented to the selector column. It is built through the DIRECT source-neutral
        // {@code expressionLambdaLogicalBinaryBodyOpenerHug}, NOT the shared {@code huggableExpressionLambdaArguments}
        // renderer the object-creation/ternary bodies use: that shared path carries a {@code plan} source-multiline entry
        // gate and a source-shaped close placement that flip this shape across passes — the reason review round 2 dropped
        // the binary hug and left binary bodies on the {@link #brokenMethodCallSegment} shape (see the helper's Javadoc).
        // Relational-with-wide-method-call bodies ({@code x -> x.f(a) == ALLOWED}, kafka {@code AuthHelper}) are not logical
        // binaries, so the helper leaves them unclaimed and they keep the broken-segment shape and stay idempotent.
        Optional<Doc> binaryHug = comments.speculatively(
            () -> expressionLambdaLogicalBinaryBodyOpenerHug.render(
                prefix,
                expression,
                compactSegmentWidth
            )
        );
        if (binaryHug.isPresent()) {
            return binaryHug.filter(DocRenderer::containsHardLine)
                    .map(hugDoc -> Doc.concat(hugDoc, finalSegmentSuffix.doc()));
        }
        boolean sourceNeutralHugBody = body instanceof ObjectCreationExpr
            || body instanceof ConditionalExpr
            || bodyIsObjectCreationRootedChain(body);
        if (!sourceNeutralHugBody) {
            return Optional.empty();
        }
        Optional<Doc> hug = comments.speculatively(
            () -> huggableExpressionLambdaArguments.render(
                prefix,
                expression.getArguments(),
                compactSegmentWidth
            )
        );
        if (hug.filter(DocRenderer::containsHardLine).isPresent()) {
            return hug.map(hugDoc -> Doc.concat(hugDoc, finalSegmentSuffix.doc()));
        }
        // Over-width segment-lambda family ({@code .map(p -> new X().setY(p))} inside a deeply-fanned chain). The shared
        // {@code huggableExpressionLambdaArguments} renderer WITHHOLDS this object-creation-rooted chain body (returns
        // empty or a degenerate FLAT one-liner) because its {@code plan} flat-fit probe measures the body at the fixed
        // CONTINUATION budget — blind to the selector's real (deeply nested) fanned continuation column — and reads the
        // flat body as fitting. Yielding here leaves the selector on {@link #brokenMethodCallSegment}, which re-renders
        // the lambda argument FLAT and over-widths at the true column. Instead build the source-neutral opener hug DIRECTLY
        // — {@code param -> new X().setY(}⏎{@code p}⏎{@code )} — through {@link #singleCallLambdaBodyOpenerHug} for the
        // SINGLE-selector object-creation-rooted body whose outermost call's arguments render flat safely (no argument that
        // only fans itself — the deferred nested-root slice). This is the SAME direct opener the method-call-body branch
        // routes a withheld single-call body through above, and it carries a forced break, so it is a valid always-broken
        // {@code conditionalGroup} fallback the enclosing group renders only when the flat selector overflows at the true
        // live column — width-safe (the hug is never wider than flat) and a fixpoint (pure function of the AST).
        if (
            body instanceof MethodCallExpr bodyChain
            && bodyIsObjectCreationRootedChain(bodyChain)
            && bodyChain.getScope().filter(MethodCallExpr.class::isInstance).isEmpty()
            && bodyChain.getArguments().stream().noneMatch(LambdaExpr.class::isInstance)
            && bodyOpenerHugArgumentsRenderFlatSafely(bodyChain)
        ) {
            LambdaExpr lambdaExpr = soleTrailingExpressionLambdaSelectorArgument(expression).orElseThrow();
            Optional<Doc> directOpener = comments.speculatively(
                () -> singleCallLambdaBodyOpenerHug(prefix, lambdaExpr, bodyChain, finalSegmentSuffix, compactSegmentWidth)
            );
            if (directOpener.filter(DocRenderer::containsHardLine).isPresent()) {
                return directOpener;
            }
        }
        // The shared renderer withheld the body (or handed back a degenerate flat one-liner) and the direct opener did not
        // claim it either: yield so the caller keeps the {@link #brokenMethodCallSegment} shape, the same fallback the
        // sibling method-call branch and the pre-D3 seam reach for every body this hug does not own.
        return Optional.empty();
    }

    /**
     * Single expression-lambda argument hugs its call opener (gjf/prettier-java, comment #3): builds the opener-hugged broken
     * layout for a FANNED chain selector whose sole argument is an expression lambda whose body is a SINGLE method call
     * ({@code .forEach((tp, partitionData) -> replicaBuffer.addFetchedData(}⏎{@code tp,}⏎…⏎{@code ))}), when the shared
     * renderer handed back the DEGENERATE FLAT one-liner (no forced break) for it because the source lambda body started on
     * the selector line ({@code compactBodyWithClosingLine} measured it at a shallow budget, blind to the selector's real
     * continuation column). The flat one-liner is not a valid {@code conditionalGroup} fallback (it renders the over-wide
     * selector flat — the {@code ReplicaVerificationTool} oscillation the round-1 seam guarded with
     * {@link #brokenMethodCallSegment}); this instead builds the opener hug DIRECTLY and SOURCE-NEUTRALLY through
     * {@link ExpressionLambdaArgumentLayout#methodCallBodyWithOpener}, so both passes render the identical hugged shape
     * regardless of whether the source lambda body was on the selector line.
     *
     * <p>Scoped to a FANNED selector ({@code segmentOnOwnLine}, stable continuation column) as the {@code else}-branch hug is.
     * Falls back to {@link #brokenMethodCallSegment} — the round-1 shape, exactly the single-segment
     * {@code compactRootWithBrokenFinalSegment} tail — when it is not a fanned selector or the opener hug is unavailable
     * ({@code methodCallBodyWithOpener} withholds an empty-argument, source-multiline-scope, or comment-dropping body), so the
     * two paths still converge for every body this direct hug does not claim.
     */
    private Doc singleCallBodyOpenerHugOrBrokenSegment(
            String prefix,
            MethodCallExpr expression,
            LambdaExpr lambdaExpr,
            MethodCallExpr bodyCall,
            MethodCallChainTail finalSegmentSuffix,
            boolean segmentOnOwnLine,
            ToIntFunction<String> compactSegmentWidth
    ) {
        // Reached only when the shared renderer handed back the DEGENERATE FLAT one-liner (no forced break) for this
        // single-call body, so re-fetching it would find the same flat shape; build the opener hug directly instead.
        if (segmentOnOwnLine) {
            Optional<Doc> directOpener = comments.speculatively(
                () -> singleCallLambdaBodyOpenerHug(prefix, lambdaExpr, bodyCall, finalSegmentSuffix, compactSegmentWidth)
            );
            if (directOpener.isPresent()) {
                return directOpener.orElseThrow();
            }
        }
        return brokenMethodCallSegment.render(expression, prefix, Doc.EMPTY, finalSegmentSuffix);
    }

    /**
     * Builds the opener hug for a fanned selector whose sole argument is a single-method-call-body expression lambda:
     * {@code .selector(params -> bodyCall(}⏎ each body argument on its own line ⏎{@code ))}. This is
     * {@link ExpressionLambdaArgumentLayout#methodCallBodyWithOpener} (the lambda header + body-call opener, body arguments
     * broken, body-call close) wrapped in the selector's {@code .selector(} … {@code )} — a pure function of the AST, so both
     * passes render it identically. Returns empty when {@code methodCallBodyWithOpener} withholds the body (empty argument
     * list, source-multiline scope, or an opener line that would drop a prefix comment), letting the caller fall back to the
     * source-neutral {@link #brokenMethodCallSegment} shape.
     */
    private Optional<Doc> singleCallLambdaBodyOpenerHug(
            String prefix,
            LambdaExpr lambdaExpr,
            MethodCallExpr bodyCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth
    ) {
        String parameters = lambdaParameters.apply(lambdaExpr);
        return expressionLambdaMethodCallBodyOpener.render(parameters, bodyCall, compactSegmentWidth)
                .map(bodyOpener -> Doc.concat(
                        Doc.text(prefix + "("),
                        bodyOpener,
                        Doc.text(")" + finalSegmentSuffix)
                ));
    }

    /**
     * Reports whether {@code body} is a method-call chain whose root is an object creation whose OUTERMOST call carries
     * arguments ({@code new VotersEndpoint().setName(…).setHost(args)}) — the object-creation-rooted body the opener-hug in
     * {@link #expressionBodyOpenerHug} can render.
     *
     * <p>The outermost-call-has-arguments guard is load-bearing: the opener hug renders the chain head on the selector line
     * and breaks the outermost call's argument list below it, so a chain that ends in an EMPTY call
     * ({@code new WorkerLoad.Builder(k).with(a, b).build()}) has no argument list to break — the shared renderer force-breaks
     * the empty {@code .build()} into a malformed {@code .build(}⏎⏎{@code )} and the shape oscillates. Such a chain keeps the
     * {@link #brokenMethodCallSegment} shape instead.
     */
    private boolean bodyIsObjectCreationRootedChain(Expression body) {
        return body instanceof MethodCallExpr bodyChain
            && methodCallChainRootIsObjectCreation.test(bodyChain)
            && !bodyChain.getArguments().isEmpty();
    }

    /**
     * Reports whether {@code bodyChain} — the method-call body of a selector's expression lambda — is a SINGLE call whose
     * receiver is a plain expression (not another method call), so {@link #brokenMethodCallSegment} can safely re-render the
     * enclosing selector's lambda argument. This is the {@code entry -> replicaBuffer.addFetchedData(a, b, c)} shape.
     *
     * <p>A body that is itself a chain (its scope is another {@link MethodCallExpr}, e.g. {@code assertThat(x).isPresent()})
     * is excluded: routing it through {@code brokenMethodCallSegment} re-renders the lambda through the generic argument
     * list, whose standalone lambda-body layout ({@code ExpressionLambdaMethodCallBodyLayout.scopedCallBodyWithHeader})
     * dereferences the inner scope-call's own scope and throws {@link java.util.NoSuchElementException} when that scope-call
     * is an unqualified static call with no scope. A body carrying any nested lambda is likewise excluded on the same
     * generic-layout path. Both keep their original hug shape (already format-twice stable) rather than the broken segment.
     */
    private boolean bodyIsSingleCallSafeForBrokenSegment(MethodCallExpr bodyChain) {
        return bodyChain.getScope().filter(MethodCallExpr.class::isInstance).isEmpty()
            && bodyChain.getArguments().stream().noneMatch(LambdaExpr.class::isInstance);
    }

    /**
     * Reports whether the direct opener hug for a single-call lambda body ({@code param -> call(args)}) would render each
     * body argument SAFELY on its own broken line — i.e. no argument is one that only fans/breaks itself and would render
     * over-wide FLAT on that line.
     *
     * <p>The opener hug ({@link ExpressionLambdaArgumentLayout#methodCallBodyWithOpener}) breaks the body call's argument
     * LIST — one argument per continuation line — but renders each argument through the ordinary argument-list path, which
     * does not fan an argument that is itself an object-creation-rooted chain ({@code results.add(new X().setA(…).setB(…))})
     * or a multi-selector method-call chain. Such an argument is exactly the deferred nested-root slice: it must reach the
     * chain printer's own fan (via the generic / broken-segment path, the shape the base already renders), not be pinned
     * flat inside the opener. Excluding it here keeps the unified opener admission scoped to the flat-argument family it was
     * designed for ({@code (tp, pd) -> replicaBuffer.addFetchedData(tp, sourceBroker.id(), partitionData)}) and yields the
     * whole seam ({@code Optional.empty()}) for the object-creation-rooted-chain body so the generic path fans it.
     */
    private boolean bodyOpenerHugArgumentsRenderFlatSafely(MethodCallExpr bodyChain) {
        return bodyChain.getArguments().stream().noneMatch(this::argumentOnlyFansItself);
    }

    private boolean argumentOnlyFansItself(Expression argument) {
        if (argument instanceof ObjectCreationExpr) {
            return true;
        }
        if (argument instanceof MethodCallExpr call) {
            return call.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
                || methodCallChainRootIsObjectCreation.test(call);
        }
        return false;
    }

    /**
     * Reports whether {@code expression}'s sole argument is an expression lambda that the source-neutral segment renderer
     * owns ({@code .map(entry -> …)}, {@code .filter(row -> …)}), returning that lambda. Scoped to the SINGLE-argument shape
     * — the expr-lambda-selector fan family ({@code stream.map(x -> x.foo()).collect(…)}) — because a selector with a leading
     * argument ({@code .onErrorResume(SomeException.class, ex -> …)}) shares the multi-argument list layout with paths this
     * seam does not own, and hugging its lambda opener here would flip opener-hug ⇄ stacked-argument-list against them. A
     * block-lambda tail keeps the generic layout too (it is not an expression lambda).
     */
    private Optional<LambdaExpr> soleTrailingExpressionLambdaSelectorArgument(MethodCallExpr expression) {
        NodeList<Expression> arguments = expression.getArguments();
        if (
            arguments.size() == 1
            && arguments.get(0) instanceof LambdaExpr lambdaExpr
            && lambdaExpr.getExpressionBody().isPresent()
        ) {
            return Optional.of(lambdaExpr);
        }
        return Optional.empty();
    }

    private boolean lambdaParametersShouldBreakInSegment(LambdaExpr lambdaExpr) {
        return lambdaExpr.getParameters()
                .stream()
                .anyMatch(parameter -> !parameter.getAllContainedComments().isEmpty());
    }

    /**
     * Re-enters the chain printer's {@code brokenMethodCallSegment} leaf: the source-neutral broken-segment shape
     * ({@code .selector(}⏎ each argument one per line ⏎{@code )}) this helper falls back to for every lambda body it does
     * not hug. It stays in the caller because the ordinary segment renderer and the single-segment tail share it.
     */
    @FunctionalInterface
    interface BrokenMethodCallSegment {
        Doc render(MethodCallExpr expression, String prefix, Doc segmentPrefix, MethodCallChainTail finalSegmentSuffix);
    }
}
