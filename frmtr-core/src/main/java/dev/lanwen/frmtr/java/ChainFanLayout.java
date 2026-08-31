package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.java.MethodCallChainPrinter.MethodCallChainTail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns the source-neutral chain-FAN machinery: whether a chain fans, and — once it is being fanned — which one-per-line
 * shape it takes.
 *
 * <p>This helper hosts the two {@link BreakRuleRegistry} that drive the canonical fan (reprint-by-default
 * break-rule model, {@code docs/proposals/reprint-by-default-break-rules.md}). The FAN-POSITION registry
 * ({@code chainFanRules}) answers "does this chain fan here?" — the single {@code canonical-fan} rule that routes a
 * fan-threshold, comment/lambda-free chain to {@link #chainFanOut} independent of the author's source shape. The FAN-SHAPE
 * registry ({@code fanShapeRules}) answers "which one-per-line shape?" once a host has committed to fanning — the
 * factory-root fold, single-selector, trivial-receiver first-selector attach, and fanned-selectors fallback. Alongside the
 * registries it hosts the AST-only fan-admission predicates every fan CALLER shares ({@link #chainFansByCanonicalRule} and
 * its trailing-comment relaxations, the binary/ternary-operand carrier {@link #binaryFansChainOperand}, the lambda-body
 * position {@link #lambdaBodyChainFansByCanonicalRule}, the width-driven two-selector / enclosed-root families) and the
 * source-neutral root builders the shapes fan onto ({@link #promotedFactoryRootDoc}, {@link #promotedObjectCreationRootDoc},
 * the trivial-receiver first-selector attach). The boundary exists so the chain printer's main decision tree can consult
 * one fan authority instead of carrying the fan registries, their candidate records, and the width-driven promotion docs
 * inline; keeping every predicate a pure function of the AST (no source-shape read, no width re-measure) is what makes the
 * fanned shape a fixpoint across passes.
 *
 * <p>The {@code promotedFactoryRootDoc} → {@code canonicalFanChain} recursion is internal to this class: a promoted
 * single-chain-argument factory call re-enters the fan through {@link #canonicalFanChain}, and the root always renders at
 * {@link LayoutContext#root()} so the fan's column threading is unchanged.
 *
 * <p>The caller still owns chain analysis, the segment renderer and its comment-claim traversal, the continuation-indent
 * shape, the width gates, the compact-source text, and the expression / object-creation / grouped-promoted-call
 * renderers — all injected as handles. This helper decides only the fan verdict and the fan shape; it never claims a
 * comment itself (a fan re-renders the root and each selector exactly once through those injected renderers, which own the
 * comment claim), and it leaves the imperative source-shape ladder, the packed shapes, and the single-segment rankers to
 * the caller for every chain the fan withholds.
 */
final class ChainFanLayout {

    private final FormatterOptions options;

    private final SourceShapePolicy sourceShapePolicy;

    private final CompactSourceText compactSource;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final ChainWidthBreakExplain chainWidthBreakExplain;

    private final Function<ObjectCreationExpr, Doc> widthDrivenObjectCreationRenderer;

    private final Function<MethodCallExpr, MethodCallChainSourcePlanner.MethodCallChainAnalysis> methodCallChainAnalysis;

    private final Predicate<MethodCallChainSourcePlanner.MethodCallChainAnalysis> chainBreaksByRule;

    private final Predicate<Expression> promotesFirstCall;

    private final Predicate<MethodCallExpr> methodCallSegmentHasComment;

    private final Predicate<MethodCallExpr> methodCallSegmentHasBlockLambdaArgument;

    private final Predicate<MethodCallExpr> methodCallSegmentHasExpressionLambdaArgument;

    private final Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment;

    private final Function<MethodCallExpr, List<JavaCommentTrivia>> finalTrailingLineComments;

    private final BiFunction<MethodCallExpr, MethodCallExpr, List<JavaCommentTrivia>> trailingLineCommentsBeforeNextSegment;

    private final BiPredicate<Expression, List<MethodCallExpr>> rootHasTrailingLineCommentBeforeFirstSegment;

    private final Function<MethodCallExpr, Doc> groupedPromotedMethodCall;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final MethodCallArgumentList methodCallArgumentList;

    private final Function<Doc, Doc> chainContinuation;

    private final BiFunction<Expression, List<Doc>, Doc> rootChainContinuation;

    private final Function<Doc, Doc> lambdaBodyChainContinuation;

    private final BiFunction<Expression, List<Doc>, Doc> lambdaBodyRootChainContinuation;

    private final BiFunction<List<MethodCallExpr>, MethodCallChainTail, List<Doc>> methodCallChainSegments;

    private final RootLineWidth rootLineWidth;

    /**
     * The fan-position break rules, resolved first-match-wins — the break-position tier, which hosts exactly one rule, the
     * canonical fan. This registry answers the same question {@link #canonicalFanChain} asked inline: fan the chain when
     * the canonical rule admits it, otherwise (no match &rarr; {@link Optional#empty()}) leave it to the imperative
     * cascade the caller falls back to. The chain-shaped {@link ChainFanRequest} candidate carries the caller-appended final-segment suffix
     * so the general {@link BreakRule}/{@link BreakRuleRegistry} abstraction hosts the chain without a leaky node-level
     * signature. The remaining fan sub-shapes inside {@link #chainFanOut} (factory-root fold, single-selector,
     * trivial-receiver attach, fanned selectors) are hosted by the sibling {@link #fanShapeRules}.
     */
    private final BreakRuleRegistry<ChainFanRequest> chainFanRules;

    /**
     * The fan SHAPE rules, resolved first-match-wins — the shape tier: the four one-per-line shapes {@link #chainFanOut}
     * chooses among once a chain is being fanned. Declaration order is precedence: the factory-root fold is tried first (it can fold a two-selector chain), then the
     * single-selector fan, then the trivial-receiver first-selector attach, and finally the always-matching
     * fanned-selectors fallback. Each rule is a pure function of its {@link ChainFanCandidate} and emits one
     * source-neutral {@link Doc}, and only the winning rule's layout runs.
     */
    private final BreakRuleRegistry<ChainFanCandidate> fanShapeRules;

    ChainFanLayout(
            FormatterOptions options,
            SourceShapePolicy sourceShapePolicy,
            CompactSourceText compactSource,
            JavaFormatRule<Expression> expressionRenderer,
            ChainWidthBreakExplain chainWidthBreakExplain,
            Function<ObjectCreationExpr, Doc> widthDrivenObjectCreationRenderer,
            Function<MethodCallExpr, MethodCallChainSourcePlanner.MethodCallChainAnalysis> methodCallChainAnalysis,
            Predicate<MethodCallChainSourcePlanner.MethodCallChainAnalysis> chainBreaksByRule,
            Predicate<Expression> promotesFirstCall,
            Predicate<MethodCallExpr> methodCallSegmentHasComment,
            Predicate<MethodCallExpr> methodCallSegmentHasBlockLambdaArgument,
            Predicate<MethodCallExpr> methodCallSegmentHasExpressionLambdaArgument,
            Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment,
            Function<MethodCallExpr, List<JavaCommentTrivia>> finalTrailingLineComments,
            BiFunction<MethodCallExpr, MethodCallExpr, List<JavaCommentTrivia>> trailingLineCommentsBeforeNextSegment,
            BiPredicate<Expression, List<MethodCallExpr>> rootHasTrailingLineCommentBeforeFirstSegment,
            Function<MethodCallExpr, Doc> groupedPromotedMethodCall,
            Function<MethodCallExpr, String> methodCallPrefix,
            MethodCallArgumentList methodCallArgumentList,
            Function<Doc, Doc> chainContinuation,
            BiFunction<Expression, List<Doc>, Doc> rootChainContinuation,
            Function<Doc, Doc> lambdaBodyChainContinuation,
            BiFunction<Expression, List<Doc>, Doc> lambdaBodyRootChainContinuation,
            BiFunction<List<MethodCallExpr>, MethodCallChainTail, List<Doc>> methodCallChainSegments,
            RootLineWidth rootLineWidth
    ) {
        this.options = options;
        this.sourceShapePolicy = sourceShapePolicy;
        this.compactSource = compactSource;
        this.expressionRenderer = expressionRenderer;
        this.chainWidthBreakExplain = chainWidthBreakExplain;
        this.widthDrivenObjectCreationRenderer = widthDrivenObjectCreationRenderer;
        this.methodCallChainAnalysis = methodCallChainAnalysis;
        this.chainBreaksByRule = chainBreaksByRule;
        this.promotesFirstCall = promotesFirstCall;
        this.methodCallSegmentHasComment = methodCallSegmentHasComment;
        this.methodCallSegmentHasBlockLambdaArgument = methodCallSegmentHasBlockLambdaArgument;
        this.methodCallSegmentHasExpressionLambdaArgument = methodCallSegmentHasExpressionLambdaArgument;
        this.methodCallChainHasFinalTrailingLineComment = methodCallChainHasFinalTrailingLineComment;
        this.finalTrailingLineComments = finalTrailingLineComments;
        this.trailingLineCommentsBeforeNextSegment = trailingLineCommentsBeforeNextSegment;
        this.rootHasTrailingLineCommentBeforeFirstSegment = rootHasTrailingLineCommentBeforeFirstSegment;
        this.groupedPromotedMethodCall = groupedPromotedMethodCall;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallArgumentList = methodCallArgumentList;
        this.chainContinuation = chainContinuation;
        this.rootChainContinuation = rootChainContinuation;
        this.lambdaBodyChainContinuation = lambdaBodyChainContinuation;
        this.lambdaBodyRootChainContinuation = lambdaBodyRootChainContinuation;
        this.methodCallChainSegments = methodCallChainSegments;
        this.rootLineWidth = rootLineWidth;
        this.chainFanRules = BreakRuleRegistry.of(List.of(
            BreakRule.of(
                "canonical-fan",
                request -> chainFansByCanonicalRuleAdmittingTrailingComment(request.expression()),
                this::canonicalFanLayout
            )
        ));
        this.fanShapeRules = BreakRuleRegistry.of(List.of(
            BreakRule.of("chain-fan-factory-root-fold", this::fanFoldsFactoryRoot, this::fanFactoryRootFoldLayout),
            BreakRule.of("chain-fan-single-selector", candidate -> candidate.calls().size() == 1, this::fanSingleSelectorLayout),
            BreakRule.of(
                "chain-fan-trivial-receiver-attach",
                this::fanAttachesTrivialReceiverFirstSelector,
                this::fanTrivialReceiverAttachLayout
            ),
            BreakRule.of("chain-fan-selectors", candidate -> true, this::fanSelectorsLayout)
        ));
    }

    /**
     * Routes a fan-threshold, comment/lambda-free chain straight to the source-neutral {@link #chainFanOut} builder,
     * <em>independent of the author's source shape</em>, and returns empty for every other chain so the caller keeps its
     * existing decision tree.
     *
     * <p>This is the shared, multi-caller sibling of the two source-neutral fan routes already inside
     * {@code MethodCallChainPrinter.methodCallChain}: the AUTO stay-flat-gate route (which fans a fitting fan-threshold
     * chain) and the early canonical-fan route (which fans a breaking one). Both of those gate on
     * {@code !sourceMultilineArguments}, so a caller reaching {@code methodCallChain} in {@code FORCED} mode on a pass
     * whose inner-selector arguments span source lines ({@code sourceMultilineArguments == true}) skips them and lands on
     * the imperative ladder below, which reads the author's source shape and can disagree with a re-format whose
     * {@code sourceMultilineArguments} has flipped to {@code false}. Emitting the {@code chainFanOut} shape here — the
     * same shape both {@code sourceMultilineArguments} passes must converge on — before the caller can consult source
     * shape removes that dependence: {@code chainFanOut} is a pure function of the AST, so both passes rebuild the
     * identical fan (a fixpoint by construction, the argument the single-segment rankers and the initializer /
     * factory-root seams already rely on).
     *
     * <p>Withheld, matching the other fan routes: a chain with any own or contained comment, any block-lambda argument,
     * or any commented segment re-renders its root once through the fan and would double-claim comments. Additionally
     * withheld for this seam: any chain whose selectors carry an expression-lambda argument or whose source-multiline
     * shape can attach an expression-lambda body — that hug↔break shape is the deliberately-deferred lambda-arrow seam,
     * left on the imperative ladder untouched.
     */
    Optional<Doc> canonicalFanChain(MethodCallExpr expression, String finalSegmentSuffix, LayoutContext layout) {
        ChainFanRequest request = new ChainFanRequest(expression, finalSegmentSuffix, layout);
        return chainFanRules.select(request).map(rule -> rule.layout(request));
    }

    /**
     * Builds the source-neutral fan for a static/factory-rooted two-selector chain
     * ({@link #chainIsWidthDrivenFan}) unconditionally, for a caller whose chain is already forced onto its own
     * multi-line shape regardless of width (a type-like-root field initializer) — the width choice this family
     * normally makes never applies there, so it always renders the fan rather than routing through
     * {@link #canonicalFanChain}'s narrower, width-blind admission.
     */
    Optional<Doc> forcedFactoryRootFanChain(MethodCallExpr expression, String finalSegmentSuffix, LayoutContext layout) {
        if (expression.getScope().isEmpty()) {
            return Optional.empty();
        }
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis.apply(expression);
        if (
            !promotesFirstCall.test(analysis.root())
            || analysis.calls().size() != 3
            || analysis.hasBlockLambdaArgument()
            || analysis.hasComments()
            || analysis.calls().stream().anyMatch(methodCallSegmentHasComment)
        ) {
            return Optional.empty();
        }
        chainWidthBreakExplain.record(expression, analysis, layout);
        return Optional.of(
            chainFanOut(analysis.root(), analysis.calls(), MethodCallChainTail.of(finalSegmentSuffix), layout)
        );
    }

    /**
     * The candidate handed to the fan-position break rules: the chain expression plus the caller-appended final-segment
     * suffix and positional context that {@link #canonicalFanLayout} needs to build the fan.
     */
    private record ChainFanRequest(MethodCallExpr expression, String finalSegmentSuffix, LayoutContext layout) {}

    /**
     * Builds the source-neutral fan {@link Doc} for a chain the canonical rule admits — the layout of the
     * {@code canonical-fan} {@link BreakRule}. Records the {@code --explain} width-break before it fans.
     */
    private Doc canonicalFanLayout(ChainFanRequest request) {
        MethodCallExpr expression = request.expression();
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis.apply(expression);
        // Record the width-break for {@code --explain} exactly as the in-{@code methodCallChain} early canonical-fan route
        // does before it fans: a chain fanned here that overflows its rendered line is a width-driven break, and the
        // explain report must attribute it as "method chain … flat width … > N available … segments, one per line" rather
        // than dropping to a bare rule-driven break. {@code ChainWidthBreakExplain#record} self-gates on {@code flatWidth > lineWidth},
        // so a chain fanned purely by the link-count/root-kind rule while it still fits records nothing (it is not a width
        // break). The width is measured at the chain's real rendered column (its {@code nodeIndentWidth} plus the caller's
        // {@code leftEdgePrefix}); this is an {@code --explain}-only diagnostic and never changes the emitted {@code Doc}.
        chainWidthBreakExplain.record(expression, analysis, request.layout());
        return chainFanOut(
            analysis.root(),
            analysis.calls(),
            MethodCallChainTail.of(request.finalSegmentSuffix()),
            request.layout()
        );
    }

    /**
     * Reports whether a chain is one {@link #canonicalFanChain} would fan: the structural fan rule fires
     * ({@code chainBreaksByRule}) and none of the carve-outs apply (own/contained comments, block-lambda arguments,
     * commented or expression-lambda selectors, or an attachable expression-lambda body). This is the exact gate
     * {@link #canonicalFanChain} applies before it emits {@code chainFanOut}, factored out so a caller can ask the
     * question without rendering the fan.
     *
     * <p>The binary/ternary-operand seam uses this: when a broken binary argument's {@code flat} rendering already
     * fans a chain operand through this rule (via the dispatched {@code chainFanOut}), the argument printer must not also
     * offer the operand-per-line {@code broken} alternative — that {@code flat}-vs-{@code broken} choice would flip the
     * operand between the fanned and flat shapes across passes. Committing the flat (chain-fanned) shape is the AST-pure
     * fixpoint the two passes converge on. Reusing this single predicate keeps the carve-outs — comment / block-lambda /
     * expression-lambda-body chains, the deferred lambda-arrow seam — identical to what {@code canonicalFanChain} withholds.
     */
    boolean chainFansByCanonicalRule(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()) {
            return false;
        }
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis.apply(expression);
        return chainBreaksByRule.test(analysis)
            && !analysis.hasComments()
            && !analysis.hasBlockLambdaArgument()
            && analysis.calls().stream().noneMatch(methodCallSegmentHasComment);
        // Expression-lambda-selector chains ({@code stream.map(x -> x.foo()).collect(...)}) are NOT withheld: they fan
        // like every other chain because {@link #sourceNeutralExpressionLambdaSegment} renders such a selector as a pure
        // function of the AST (a conditional group of flat vs. hug/fan), so the fanned chain is idempotent. Only the
        // comment / block-lambda chains withheld above stay off the fan.
    }

    /**
     * Reports whether a chain is one of the three families that fan by WIDTH rather than by the author's line breaks —
     * the shapes {@code chainBreaksByRule} does NOT already claim structurally.
     *
     * <ul>
     *   <li><strong>Trivial-receiver two- or three-selector.</strong> A bare {@code NameExpr}/{@code FieldAccessExpr}/
     *       {@code this}/{@code super} receiver ({@link #chainRootIsTrivialReceiver}) with exactly two or three selectors
     *       ({@code dataMap.computeIfAbsent(k).put(v)}, {@code orderEvent.validateOrder().deliveryPlan()},
     *       {@code x.stream().collect(...)}). Both counts are below the canonical link-count threshold, so the
     *       width-driven arm fans when the flat line overflows and keeps it flat otherwise.</li>
     *   <li><strong>Call-rooted two-selector.</strong> A {@link MethodCallExpr} root with exactly two selectors
     *       ({@code expectThat(result).as("round-trip").isNotNull()},
     *       {@code service.resolve(req).as("snapshot").isPresent()}). Below the call-root threshold (three), so the
     *       width-driven arm keeps it flat when it fits and fans on overflow.</li>
     *   <li><strong>Static/factory-rooted two-selector.</strong> A type-like qualifier root
     *       ({@link MethodCallChainSourcePlanner#promotesFirstCall}) whose factory call is folded into {@code calls()},
     *       with exactly two selectors after it ({@code ConnectionPolicy.newBuilder().setEndpoint(...).setProtocol(...)}),
     *       and no block-lambda argument (a mid-chain block lambda can't share the flat compact arm this family builds).
     *       Below the static/factory threshold (three selectors after the factory call), so the width-driven arm keeps it
     *       flat when it fits and fans on overflow.</li>
     *   <li><strong>Enclosed / cast-rooted fanning chain.</strong> A parenthesized (or parenthesized-cast) root
     *       whose inner chain itself fans ({@link #rootIsEnclosedFanningChain}), e.g.
     *       {@code ((OffsetFetchRequestData) res.unsentRequests.get(0)...data()).groups().forEach(...)}. The width-driven
     *       arm fans it source-neutrally.</li>
     * </ul>
     *
     * <p>Keyed only on the root's AST kind and the link count — no source-shape read — so the fan verdict is a fixpoint.
     * The arm gates the fan itself on WIDTH (the {@code bestFitting} flat-vs-fan choice), which is the point: these
     * families fan by width, not by source shape.
     */
    boolean chainIsWidthDrivenFan(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        Expression root = analysis.root();
        int links = analysis.calls().size();
        if (chainRootIsTrivialReceiver(root) && (links == 2 || links == 3)) {
            return true;
        }
        if (root instanceof MethodCallExpr && links == 2) {
            return true;
        }
        if (promotesFirstCall.test(root) && links == 3 && !analysis.hasBlockLambdaArgument()) {
            return true;
        }
        return rootIsEnclosedFanningChain(root) && links >= 1;
    }

    /**
     * Reports whether a width-driven chain must fan when the caller renders it FORCED — the trivial-receiver
     * three-selector family, the only one {@link MethodCallChainPrinter}'s forced width-driven route fans
     * unconditionally (its {@code calls.size() == 3} branch) rather than ranking flat-vs-fan by width.
     */
    boolean chainFansByWidthWhenForced(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()) {
            return false;
        }
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis.apply(expression);
        return chainIsWidthDrivenFan(analysis)
            && analysis.calls().size() == 3
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis))
            && analysis.calls().stream().noneMatch(methodCallSegmentHasComment);
    }

    /**
     * Reports whether {@code argument} is a lambda whose body forces its own multi-line layout — a block lambda (its
     * {@code { ... }} always breaks) or an expression lambda whose body itself nests a lambda. The width-driven two-selector
     * fan ({@link #chainIsWidthDrivenFan}) keeps such a chain on the {@link Doc#bestFitting} arm rather than
     * {@link Doc#conditionalGroup}: the body can never render flat, so a conditionalGroup would fan the receiver on every
     * pass while the standalone lambda-body renderer still shapes the body from the author's line breaks (the deferred
     * lambda-arrow keystone), and the two would oscillate. Keeping bestFitting preserves the pre-change shape.
     */
    boolean lambdaArgumentForcesMultilineBody(Expression argument) {
        if (!(argument instanceof LambdaExpr lambda)) {
            return false;
        }
        return lambda.getExpressionBody()
                .map(body -> !body.findAll(LambdaExpr.class).isEmpty())
                .orElse(true);
    }

    /**
     * The {@link #canonicalFanChain} entry gate: {@link #chainFansByCanonicalRule} PLUS the one comment relaxation the fan
     * position can absorb — a chain whose only comment is a last-selector trailing line comment
     * ({@link #chainCommentsAreOnlyTrailingLine}). This is deliberately NOT folded into {@code chainFansByCanonicalRule}
     * itself: that predicate is also the shared carve-out gate for the binary-operand ({@link #binaryFansChainOperand}),
     * lambda-body ({@link #lambdaBodyChainFansByCanonicalRule}), and enclosed-root ({@link #rootIsEnclosedFanningChain})
     * deciders, which must keep withholding every comment-bearing chain (their flat-vs-broken commits do not own the
     * comment-preserving segment render). Only the direct fan positions ({@code canonicalFanChain} and the with-tail seam)
     * admit the trailing-comment chain, where the fan renders the whole chain once and preserves the last selector's comment.
     */
    boolean chainFansByCanonicalRuleAdmittingTrailingComment(MethodCallExpr expression) {
        if (chainFansByCanonicalRule(expression)) {
            return true;
        }
        if (expression.getScope().isEmpty()) {
            return false;
        }
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis.apply(expression);
        return chainBreaksByRule.test(analysis)
            && !analysis.hasBlockLambdaArgument()
            && analysis.calls().stream().noneMatch(methodCallSegmentHasComment)
            && chainCommentsAreOnlyTrailingLine(analysis);
    }

    /**
     * Reports whether a chain's ONLY comment is a single trailing line comment on its LAST selector
     * ({@code .streams()}⏎{@code .get(0)}⏎{@code .streamArn(); // XXX}) — the one comment shape the source-neutral
     * {@link #chainFanOut} provably preserves without a placement hazard, because {@code methodCallChainSegments} re-emits
     * that last selector's {@code finalTrailingLineComment} slot.
     *
     * <p>Deliberately narrow. Every other comment family is excluded so the chain keeps the comment-preserving imperative
     * path, because {@code chainFanOut} would drop or destabilize it:
     * <ul>
     *   <li>a root-contained / root-to-first-selector block comment or the root's own trailing comment — the fan re-renders
     *       the root through plain expression dispatch, which does not carry it;</li>
     *   <li>a selector's leading / name / argument-gap comment ({@code methodCallSegmentHasComment}) — reserved for the
     *       comment-carrying selector paths;</li>
     *   <li>a trailing comment on the FIRST call — for a factory root that call is PROMOTED onto the root line
     *       ({@code SubjectFactory.create() // primary subject}) and for a trivial-receiver root it is ATTACHED as bare text
     *       ({@link #attachedFirstSelectorSegment}); neither promotion nor the bare-text attach carries the comment, so it
     *       would be dropped (the {@code method-chain-trailing-empty-call-comment} fixture);</li>
     *   <li>a trailing comment in a BETWEEN-selector gap ({@code .a() // note}⏎{@code .b()}) — JavaParser attaches such a
     *       comment as leading-of-{@code .b()} vs trailing-of-{@code .a()} depending on the surrounding whitespace, so a
     *       collapse/re-expand can move it and the fan-vs-imperative verdict would follow (the {@code chain-lambda-nested-comment}
     *       perturbation). Only the after-LAST-selector slot is placement-stable.</li>
     * </ul>
     *
     * <p><strong>Why the fan must claim the last-selector case.</strong> JavaParser parks a chain's final trailing line
     * comment (the {@code // XXX} after {@code .streamArn();}) on the STATEMENT when the flat chain shares that source line
     * with the terminator, but on the LAST SELECTOR once the chain is broken across lines. That flips
     * {@code MethodCallChainAnalysis.hasComments} between passes: a flat-source pass reads the chain comment-free and fans
     * it through {@code chainFanOut} (attaching the first selector), while the broken re-format reads
     * the comment on the selector, withholds the fan, and drops to the source-shape imperative ladder that fans from the
     * first selector — {@code streamsListResult.streams()} ⇄ {@code streamsListResult}⏎{@code .streams()} forever. Letting
     * the fan claim this one shape routes BOTH passes through the same source-neutral {@code chainFanOut}, so the placement
     * flip does not select divergent layouts (the camel {@code ShardIteratorHandler} / {@code CsvDataFormat} /
     * {@code DefaultSupervisingRouteController} / {@code ExportBaseCommand} cases).
     */
    boolean chainCommentsAreOnlyTrailingLine(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        List<MethodCallExpr> calls = analysis.calls();
        return analysis.hasTrailingLineComments()
            && !analysis.rootHasComments()
            && !rootHasTrailingLineCommentBeforeFirstSegment.test(analysis.root(), calls)
            && calls.stream().noneMatch(methodCallSegmentHasComment)
            && chainTrailingLineCommentOnlyOnLastCall(calls);
    }

    /**
     * Reports that the chain carries a trailing line comment on its LAST selector and on NO earlier selector, so the only
     * trailing comment sits in the placement-stable after-last-selector slot {@code methodCallChainSegments} re-emits (see
     * {@link #chainCommentsAreOnlyTrailingLine}). A comment in a between-selector gap or on the first (promoted/attached)
     * call fails this, keeping the chain on the comment-preserving imperative path.
     */
    private boolean chainTrailingLineCommentOnlyOnLastCall(List<MethodCallExpr> calls) {
        if (calls.isEmpty() || finalTrailingLineComments.apply(calls.getLast()).isEmpty()) {
            return false;
        }
        for (int index = 0; index + 1 < calls.size(); index++) {
            if (!trailingLineCommentsBeforeNextSegment.apply(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether {@code expression} is a canonical fan ({@link #chainFansByCanonicalRule}) that carries a trailing
     * line comment — the exact chains whose {@code hasComments} placement flips between passes (see
     * {@link #chainCommentsAreOnlyTrailingLine}). A with-tail caller uses this to route such a chain through the
     * source-neutral {@code canonicalFanChain} on EVERY pass regardless of the caller's statement/return/initializer
     * position, so the flip does not select the imperative fan-from-first shape on the pass that sees the comment. A
     * comment-free fan is excluded here (it is not the flip case) and keeps its existing position-specific routing
     * untouched; a chain with any non-trailing comment is already excluded by {@code chainFansByCanonicalRule}.
     */
    boolean chainFansByCanonicalRuleWithTrailingLineComment(MethodCallExpr expression) {
        return methodCallChainHasFinalTrailingLineComment.test(expression)
            && chainFansByCanonicalRuleAdmittingTrailingComment(expression);
    }

    /**
     * Reports whether {@code expression} is a binary or ternary expression that contains a flattened operand which the
     * canonical-fan rule fans ({@link #chainFansByCanonicalRule}). Such an expression's dispatched flat
     * rendering hard-breaks that operand into a source-neutral {@code chainFanOut}, so any caller that would otherwise
     * offer a source-shape-gated operand-per-line broken alternative must instead commit the flat (chain-fanned) shape —
     * it is the AST-pure fixpoint the two passes converge on.
     *
     * <p>The binary/logical/string-concat OPERAND carrier of the canonical fan.
     * This is the shared carve-out gate for every binary-argument / binary-initializer decider whose flat arm already
     * fans a chain operand: {@link BreakableArgumentExpressionPrinter} uses the equivalent recursion on the
     * method-call/object-creation argument-list path; the same question is asked by {@link MethodCallPrinter}'s
     * single-binary-argument path (the {@code assertTrue(chain.isPresent() && chain2)} / {@code println("..." + chain)}
     * carrier that renders a forced operand-per-line break on a flat-source pass but fans the operand on a
     * source-multiline pass) and by {@link VariableInitializerLayout}'s broken object-creation binary argument (the
     * {@code new StatusData(chain * step + min, …)} carrier). Factoring the predicate here — beside
     * {@link #chainFansByCanonicalRule}, the rule it delegates to — keeps the carve-outs (comment / block-lambda /
     * expression-lambda-body chains, the deferred lambda-arrow seam) identical across every caller.
     *
     * <p>The recursion descends {@code BinaryExpr} operands, {@code EnclosedExpr}/{@code ConditionalExpr} branches, and a
     * leading {@code CastExpr} so a chain operand nested under parentheses, a ternary branch, a mixed-operator sub-binary
     * ({@code chain * step + min}), or a cast ({@code (Double) chain.metricValue() > 0.0}) is still found. It only inspects
     * the AST and never claims a comment.
     */
    boolean binaryFansChainOperand(Expression expression) {
        if (!(expression instanceof BinaryExpr) && !(expression instanceof ConditionalExpr)) {
            return false;
        }
        return operandFansChainByRule(expression);
    }

    private boolean operandFansChainByRule(Expression expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return operandFansChainByRule(binaryExpr.getLeft()) || operandFansChainByRule(binaryExpr.getRight());
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return operandFansChainByRule(enclosedExpr.getInner());
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return operandFansChainByRule(conditionalExpr.getThenExpr())
                || operandFansChainByRule(conditionalExpr.getElseExpr());
        }
        if (expression instanceof CastExpr castExpr) {
            return operandFansChainByRule(castExpr.getExpression());
        }
        return expression instanceof MethodCallExpr methodCall && chainFansByCanonicalRule(methodCall);
    }


    /**
     * Lambda-body position gate: reports whether a chain in an expression-lambda body should fan one selector per
     * line. Covers the structural-threshold family ({@link #chainFansByCanonicalRule}) and three-selector
     * trivial-receiver chains, which fan unconditionally here even when flat would fit at the rendered column —
     * lambda-body position warrants the stable canonical shape. Object-creation roots are excluded because
     * {@code chainFanOut} renders them at column zero and oscillates across passes.
     */
    boolean lambdaBodyChainFansByCanonicalRule(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()) {
            return false;
        }
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis.apply(expression);
        Expression root = analysis.root();
        if (root instanceof ObjectCreationExpr) {
            return false;
        }
        if (chainRootIsTrivialReceiver(root)
                && analysis.calls().size() == 3
                && !analysis.hasComments()
                && !analysis.hasBlockLambdaArgument()
                && analysis.calls().stream().noneMatch(methodCallSegmentHasComment)) {
            return true;
        }
        return chainFansByCanonicalRule(expression);
    }

    /**
     * Builds the source-neutral fan {@link Doc} for a chain a host has committed to fanning: the root on its opening line,
     * then each selector on its own dotted continuation line, the final selector carrying {@code tail}. The one-per-line
     * SHAPE is chosen by the {@link #fanShapeRules} first-match-wins (factory-root fold, single-selector,
     * trivial-receiver first-selector attach, or the fanned-selectors fallback).
     *
     * <p>Each segment still renders through the ordinary {@code methodCallChainSegment} group, so a segment stays flat
     * when {@code .selector(args)} fits at its continuation column and opens its own argument list only on genuine
     * overflow — the per-segment argument decision stays with the renderer. It builds one {@link Doc} and renders each
     * call exactly once, so it is comment-neutral and never double-claims a comment; callers that emit it as one arm of a
     * two-arm {@link Doc#bestFitting(java.util.List) bestFitting} still gate that emission on the chain being comment-free.
     */
    Doc chainFanOut(
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainTail tail,
            LayoutContext layout
    ) {
        ChainFanCandidate candidate = new ChainFanCandidate(root, calls, tail, layout);
        return fanShapeRules.select(candidate)
            .orElseThrow(() -> new IllegalStateException("no chain-fan shape rule matched"))
            .layout(candidate);
    }

    /**
     * The decomposed parts of a chain a fan host has already committed to fanning, handed to the {@link #fanShapeRules}.
     * Positional context travels on {@code layout}; the shape rules read only these AST-derived facts.
     */
    private record ChainFanCandidate(
        Expression root,
        List<MethodCallExpr> calls,
        MethodCallChainTail tail,
        LayoutContext layout
    ) {}

    // A hugged lambda body threads its {@code param -> } header as the chain's leftEdgePrefix, so an arrow-terminated
    // prefix marks a fan sitting in lambda-body position — the only place the reduced +4 continuation is offered.
    private boolean fanHugsLambdaBody(ChainFanCandidate candidate) {
        return candidate.layout().leftEdgePrefix().stripTrailing().endsWith("->");
    }

    // Ranks a lambda-body fan's +8 continuation against a width-safe +4 one and lets the renderer pick at the true
    // column: {@code fanAt8} carries the higher priority so it wins whenever it fits (byte-identical to the ordinary
    // fan), and {@code fanAt4} — narrower, so lower priority but the always-renderable last arm — is chosen only when +8
    // overflows (fit gate) or, if both overflow, as the least-overflow fallback. {@code buildFan} renders the whole fan
    // for a given single-{@link Doc} continuation; a non-lambda-body fan just builds once at +8. Ranking each nested hop
    // at its own column relies on the memoized, uncapped best-fitting (no depth truncation).
    private Doc widthRankedFan(ChainFanCandidate candidate, Function<Function<Doc, Doc>, Doc> buildFan) {
        if (!fanHugsLambdaBody(candidate)) {
            return buildFan.apply(chainContinuation);
        }
        return Doc.bestFitting(
            List.of(buildFan.apply(chainContinuation), buildFan.apply(lambdaBodyChainContinuation)),
            new int[] {1, 0}
        );
    }

    // The root-anchored analogue of {@link #widthRankedFan}: {@code buildFan} takes a root-continuation
    // ({@code (root, segments) -> Doc}) so the fanned-selectors and factory-root shapes can rank +8 against +4 the same way.
    private Doc widthRankedRootFan(ChainFanCandidate candidate, Function<BiFunction<Expression, List<Doc>, Doc>, Doc> buildFan) {
        if (!fanHugsLambdaBody(candidate)) {
            return buildFan.apply(rootChainContinuation);
        }
        return Doc.bestFitting(
            List.of(buildFan.apply(rootChainContinuation), buildFan.apply(lambdaBodyRootChainContinuation)),
            new int[] {1, 0}
        );
    }

    // Factory / type-like root seam: a {@code promotesFirstCall} root (an uppercase {@code NameExpr} or
    // {@code FieldAccessExpr} type qualifier, e.g. {@code ClusterConfig}) with two or more calls folds its FIRST call —
    // the factory invocation ({@code .defaultBuilder()}) — onto the root line and fans only the remaining selectors,
    // {@code Type.factory()}⏎{@code .next()}⏎{@code .build()}. This mirrors {@code MethodCallChainSourcePlanner.plan}'s
    // first-call promotion for a static/factory root (which counts that factory call as part of the root — the
    // {@code calls - 1} arm of {@link MethodCallChainSourcePlanner#chainBreaksByRule}), so the RENDERING now agrees with
    // the RULE'S link counting. It also makes the whole chain source-neutral: the early canonical-fan route reaches
    // {@code chainFanOut} on a flat-source pass, while a re-format whose inner selector arguments now span source lines
    // ({@code sourceMultilineArguments}) skips the early route and lands on {@code plan}'s promotion tail below — both
    // must produce the identical {@code Type.factory()}-on-the-root-line shape or the chain flips split<->attach forever.
    // The factory call reaching here is always source-compact (a source-multiline factory call would have tripped
    // {@code sourceMultilineArguments} and skipped the early route), so {@link #promotedFactoryRootDoc} renders it
    // through the same width-driven promotion doc {@code plan}'s {@code GROUPED_PROMOTED_METHOD_CALL} /
    // {@code EXPRESSION_RENDERER} rootRendering produces — byte-identical, and idempotent because that doc is a pure
    // function of the AST plus the render column.
    //
    // A factory call carrying an expression lambda folds onto the root line in two source-neutral cases: (a) its whole
    // compact form
    // fits flat ({@link #expressionLambdaFactoryCallPromotesFlat} — {@code IntStream.iterate(50, n -> n + 7)}), and
    // (b) it has TWO OR MORE arguments ({@link #expressionLambdaFactoryCallFoldsAsMultiArgGroup} —
    // {@code Flux.usingWhen(connectionFactory.create(), connection -> …, Connection::close)}), which
    // {@link #promotedFactoryRootDoc} renders through its width-driven multi-argument {@link Doc#group} — {@code
    // Flux.usingWhen(} on the root line, arguments fanned one per line, {@code )} dedented — never through
    // {@code groupedPromotedMethodCall}'s source-shape-sensitive lambda-hug branches. Both routes are a pure function
    // of the AST plus the render column, so the fold stays a fixpoint. A SINGLE expression-lambda-argument factory call
    // that does not promote flat ({@code Type.of(x -> body)}) is still held back: it would route through
    // {@code groupedPromotedMethodCall}'s {@code groupedPromotedExpressionLambda} / packed-body branches, which read the
    // author's source shape, so it stays on the split shape until the deferred lambda-arrow seam lands. A block-lambda
    // factory call is likewise held back.
    private boolean fanFoldsFactoryRoot(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        return promotesFirstCall.test(candidate.root())
            && calls.size() >= 2
            && !methodCallSegmentHasBlockLambdaArgument.test(calls.getFirst())
            && (!methodCallSegmentHasExpressionLambdaArgument.test(calls.getFirst())
                || expressionLambdaFactoryCallPromotesFlat(calls.getFirst())
                || expressionLambdaFactoryCallFoldsAsMultiArgGroup(calls.getFirst()));
    }

    private Doc fanFactoryRootFoldLayout(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        MethodCallExpr factoryCall = calls.getFirst();
        List<MethodCallExpr> selectors = new ArrayList<>(calls.subList(1, calls.size()));
        List<Doc> segments = methodCallChainSegments.apply(selectors, candidate.tail());
        return widthRankedRootFan(candidate, continuation -> Doc.concat(
            promotedFactoryRootDoc(factoryCall),
            continuation.apply(factoryCall, segments)
        ));
    }

    // Object-creation root seam, the constructor-root analogue of the factory-root promotion
    // above: a comment-free, non-anonymous, non-empty-argument {@code new Type(args)} root renders SOURCE-NEUTRALLY
    // through {@link #promotedObjectCreationRootDoc} (a width-driven {@code Doc.group} of the constructor argument
    // list), so the constructor arguments break by the renderer's width verdict at the true column on every pass
    // rather than through {@code ObjectCreationPrinter}'s source-multiline preservation or the imperative
    // fall-through's {@code brokenObjectCreationRenderer} force-break. This is what lets the fall-through route a
    // constructor-rooted fan-threshold chain through this builder and converge with the flat-selector pass — see
    // {@link #promotedObjectCreationRootDoc}. Roots outside that scope keep the plain {@code expressionRenderer.format}
    // doc. Shared by the fan shapes that keep the root on its own opening line (single-selector, trivial-receiver
    // attach, fanned selectors); the factory-root fold renders its own root and does not call this.
    private Doc fanRootDoc(Expression root) {
        return objectCreationRootIsWidthDrivenFanEligible(root)
            ? promotedObjectCreationRootDoc((ObjectCreationExpr) root)
            : expressionRenderer.format(root, LayoutContext.root());
    }

    // Single selector: the lone segment fans onto its own dotted continuation line through the SAME on-own-line
    // segment renderer as the multi-selector fan ({@code methodCallChainSegments}), so its force-break decision is
    // measured at the continuation column. The single-Doc {@code chainContinuation} wrap (never the multi-selector's
    // short-root padding branch) keeps the rendered indentation at the plain single-selector column.
    private Doc fanSingleSelectorLayout(ChainFanCandidate candidate) {
        Doc segment = methodCallChainSegments.apply(candidate.calls(), candidate.tail()).getFirst();
        return widthRankedFan(candidate, continuation -> Doc.concat(
            fanRootDoc(candidate.root()),
            continuation.apply(segment)
        ));
    }

    // Trivial-receiver first-selector attach (gjf/prettier-java). When the chain root is a TRIVIAL RECEIVER — a bare
    // {@code NameExpr}/{@code FieldAccessExpr}/{@code this}/{@code super} (see {@link #chainRootIsTrivialReceiver}), NOT
    // a call/factory/constructor root — the FIRST selector stays glued to the root on the opening line and the fan
    // begins at the SECOND selector ({@code orderEvent.validateOrder()}⏎{@code .deliveryPlan()}…, {@code response
    // .unsentRequests.get(0)}⏎{@code .requestBuilder()}…). This matches google-java-format / prettier-java, which anchor
    // the first segment on the receiver and only fan the builder tail below it. It is a DETERMINISTIC STRUCTURAL rule
    // keyed strictly on the root kind — never on width or the author's source shape — so it stays a fixpoint by
    // construction: both passes see the same root kind and rebuild the identical attach. A call/factory/constructor
    // root keeps the fan-from-first shape above/below. The attached first selector renders through the ordinary (not
    // on-own-line) segment group, so {@code .selector(args)} stays flat when it fits at the root's live column and opens
    // its own argument list only on genuine overflow, exactly like the single-selector case; the remaining selectors
    // fan one per line under the same continuation indent, the final one carrying the tail.
    //
    // Gated on {@code calls.size() >= 2}: the attach extends from the canonical fan (three or more
    // selectors, {@code chainBreaksByRule}'s plain-receiver threshold) down to a sub-threshold TWO-selector chain that
    // reached this builder because its flat form was over-width ({@code entry.state().shouldPrioritize(subject.owner())}).
    // The concern with the old
    // {@code >= 3} gate was that an over-wide {@code root.firstSelector(args)} opener whose OWN argument list breaks would
    // flip across passes; the two gates below make that impossible for the shapes admitted here, so the extension stays a
    // fixpoint (corpus idempotence unchanged): the first selector is rendered as atomic text that never opens its own
    // argument list ({@link #firstSelectorAttachesSafely}), and the fan/no-fan decision itself is a pure width probe on
    // the flat compact form (invariant across passes), which attaching cannot change. A first selector that is NOT
    // attach-safe still keeps the fan-from-first shape below.
    //
    // Additionally gated on the first selector being ATTACH-SAFE ({@link #firstSelectorAttachesSafely}): no arguments or
    // only simple leaf arguments ({@code .getRange()}, {@code .get(0)}, {@code .entrySet()}, {@code .validateOrder()}),
    // so it renders as one atomic {@code .selector(...)} token that NEVER opens its own broken argument list. A NON-LEAF
    // first selector ({@code builder.stream("input", Consumed.as("source"))}) still attaches when
    // {@link #firstSelectorAttachesFlat} finds its flat compact form fits at the deterministic attached column (root
    // column + root length) — atomic flat text never breaks internally, so it cannot reindent across passes either. A
    // first selector with a lambda, nested call, or multi-argument list that does NOT fit flat ({@code
    // target.computeIfAbsent(topicId, __ -> new X() …)}) can break INTERNALLY, and that inner break's indentation is
    // measured relative to the segment's live column — which shifts once the previous pass glued the selector to the
    // root — so the attached block reindents across passes (the kafka {@code ConsumerGroupMember} oscillation). Such a
    // chain keeps the fan-from-first shape below ({@code target}⏎{@code .computeIfAbsent(…)}⏎…), where the selector's
    // argument list breaks at a stable continuation column.
    //
    // A SHORT receiver ({@code env}, {@code p}, {@code res}) attaches too: {@link #fanTrivialReceiverAttachLayout} renders
    // the fanned tail at the plain continuation indent, never {@code chainContinuation}'s short-root PADDING branch, so the
    // attached opener ({@code env.adminClient()}) anchors the tail. The bare short root no longer sits alone on its line
    // ({@code env}⏎padded{@code .adminClient()}), the shape that used to diverge from the fan-from-first fall-through.
    private boolean fanAttachesTrivialReceiverFirstSelector(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        return calls.size() >= 2
            && chainRootIsTrivialReceiver(candidate.root())
            && (firstSelectorAttachesSafely(calls.getFirst())
                || firstSelectorAttachesFlat(calls.getFirst(), candidate.layout())
                || bareNameReceiverFirstSelectorHugsLambda(candidate.root(), calls.getFirst()));
    }

    // Bare-name-receiver lambda-selector hug. Extends the trivial-receiver first-selector attach to a
    // first selector whose sole trailing argument is an EXPRESSION LAMBDA ({@code probe.withVirtualTime(() -> …)}), so the
    // receiver name and its lambda-carrying first selector stay on the opening line rather than fanning the receiver onto
    // its own line ({@code return probe}⏎{@code .withVirtualTime(…)}). Restricted to a bare {@code NameExpr} receiver — NOT
    // the broader trivial-receiver set: a {@code FieldAccessExpr} or {@code this}/{@code super} root keeps the fan-from-first
    // shape for the lambda case. The attached selector is rendered
    // through the ordinary source-neutral segment renderer (NOT the atomic-text {@link #attachedFirstSelectorSegment}), so
    // its expression-lambda body breaks by WIDTH at the attached column exactly like a fanned selector would; because the
    // attach itself is a deterministic structural verdict keyed only on the root/selector AST kind, both passes rebuild the
    // identical attached shape and the body's width-driven break is measured at the same (attached) column, keeping the
    // layout a fixpoint.
    private boolean bareNameReceiverFirstSelectorHugsLambda(Expression root, MethodCallExpr firstSelector) {
        return root.isNameExpr() && firstSelectorHugsExpressionLambda(firstSelector);
    }

    private boolean firstSelectorHugsExpressionLambda(MethodCallExpr firstSelector) {
        if (firstSelector.getTypeArguments().isPresent() || methodCallSegmentHasComment.test(firstSelector)) {
            return false;
        }
        NodeList<Expression> arguments = firstSelector.getArguments();
        return arguments.size() == 1
            && arguments.get(0) instanceof LambdaExpr lambda
            && lambda.getExpressionBody().isPresent();
    }

    private Doc fanTrivialReceiverAttachLayout(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        List<MethodCallExpr> fannedSelectors = new ArrayList<>(calls.subList(1, calls.size()));
        // The fanned tail hangs at the plain continuation indent, anchored on the attached opener, not on the bare root:
        // a short root ({@code env}) would otherwise trip {@code chainContinuation}'s root-padding branch and align the
        // tail under the short root. For a long root this is byte-identical to the root-continuation it replaces. A
        // lambda-body fan ranks the tail's +8 against a +4 so a deep hop drops to +4 only when +8 overflows.
        Doc tail = Doc.join(Doc.HARD_LINE, methodCallChainSegments.apply(fannedSelectors, candidate.tail()));
        Doc attached = widthRankedFan(candidate, continuation -> Doc.concat(
            fanRootDoc(candidate.root()),
            attachedFirstSelectorDoc(calls.getFirst(), candidate.layout()),
            continuation.apply(tail)
        ));
        if (postAttachTailIsLoneCall(fannedSelectors)) {
            Doc hugged = Doc.concat(
                fanRootDoc(candidate.root()),
                attachedFirstSelectorDoc(calls.getFirst(), candidate.layout()),
                flatTailCallDoc(fannedSelectors.getFirst(), candidate.tail())
            );
            // Ranked by FIRST-LINE fit, not plain line count: the hugged arm is one (possibly long) line, while the
            // fanned {@code attached} arm's own opening line is always short (just the attached opener). Plain
            // fewest-lines ranking would let an overflowing one-line hug beat a fanned shape whose LATER continuation
            // line still overflows less (or not at all) once its own argument list explodes further — first-line-fit
            // gates that out, so the hug only wins when it is actually narrow enough to sit on the closer's line.
            attached = Doc.bestFittingFirstLine(List.of(hugged, attached));
        }
        // {@code bestFitting} ranks the attach-safe leaf/flat-fitting first selector against the fan-from-first shape so an
        // overflowing opener breaks after the receiver instead of stranding it over width. A lambda-carrying first selector
        // ({@link #bareNameReceiverFirstSelectorHugsLambda}) is NOT ranked — it would flip the fan even where the hug fits.
        if (firstSelectorAttachesSafely(calls.getFirst()) || firstSelectorAttachesFlat(calls.getFirst(), candidate.layout())) {
            return Doc.bestFitting(List.of(attached, fanSelectorsLayout(candidate)));
        }
        return attached;
    }

    /**
     * Reports whether the post-attach tail ({@link #fanTrivialReceiverAttachLayout}'s {@code fannedSelectors}) is a
     * single, comment-free call eligible to hug onto the attached opener's closing line ({@code
     * )).expectSubscription();}, {@code )).expectNextCount(4);}) instead of fanning onto its own line — the actual
     * hug is still gated on the flat text fitting ({@link #flatTailCallDoc} ranked by {@code bestFitting} in
     * {@link #fanTrivialReceiverAttachLayout}). Comment-bearing calls are excluded: {@code methodCallChainSegments}
     * would still emit their comment prefix as its own hard line, so hugging would not actually collapse to one line.
     * A lambda-argument or type-argument call is excluded too: {@link #flatTailCallDoc} reconstructs the call as
     * plain compact text, and a lambda body collapsed onto one line reads worse than the fanned shape it would
     * replace, so those tails keep the fan-from-first shape.
     */
    private boolean postAttachTailIsLoneCall(List<MethodCallExpr> fannedSelectors) {
        if (fannedSelectors.size() != 1) {
            return false;
        }
        MethodCallExpr tailCall = fannedSelectors.getFirst();
        return tailCall.getTypeArguments().isEmpty()
            && !methodCallSegmentHasBlockLambdaArgument.test(tailCall)
            && !methodCallSegmentHasExpressionLambdaArgument.test(tailCall)
            && !methodCallSegmentHasComment.test(tailCall)
            && !sourceShapePolicy.hasContainedComments(tailCall)
            && finalTrailingLineComments.apply(tailCall).isEmpty();
    }

    /**
     * Builds the lone trailing call's FLAT compact text ({@code .expectNextCount(4);}) for the hug arm in
     * {@link #fanTrivialReceiverAttachLayout} — plain {@link Doc#text}, so a fit-gated {@code bestFitting} can only
     * pick it whole or fall back to the fanned shape, never partially break the call's own argument list.
     */
    private Doc flatTailCallDoc(MethodCallExpr tailCall, MethodCallChainTail tail) {
        return tail.appendTo(Doc.text(
            "." + tailCall.getNameAsString() + "(" + compactSource.compactJoin(tailCall.getArguments()) + ")"
        ));
    }

    // Renders the attached first selector for {@link #fanTrivialReceiverAttachLayout}. An attach-SAFE leaf selector or a
    // non-leaf selector whose flat form fits at the attached column ({@link #firstSelectorAttachesFlat}) renders as a
    // single {@code .selector(...)} token that never breaks at a selector boundary ({@link #attachedFirstSelectorSegment});
    // a bare-name-receiver lambda-carrying first selector renders through the ordinary source-neutral segment renderer so
    // its lambda body can break by width at the attached column ({@link #bareNameReceiverFirstSelectorHugsLambda}). The
    // lambda selector is the sole element of the segment list, so the renderer measures it as a standalone segment with no
    // trailing suffix.
    private Doc attachedFirstSelectorDoc(MethodCallExpr firstSelector, LayoutContext layout) {
        if (firstSelectorAttachesSafely(firstSelector) || firstSelectorAttachesFlat(firstSelector, layout)) {
            return attachedFirstSelectorSegment(firstSelector);
        }
        return methodCallChainSegments.apply(List.of(firstSelector), MethodCallChainTail.of("")).getFirst();
    }

    // Multi-segment: one selector per line under the continuation indent, the same one-per-line layout the imperative
    // broken-chain tail produces (each segment measured at the continuation column, the final one carrying the tail).
    private Doc fanSelectorsLayout(ChainFanCandidate candidate) {
        List<Doc> segments = methodCallChainSegments.apply(candidate.calls(), candidate.tail());
        return widthRankedRootFan(candidate, continuation -> Doc.concat(
            fanRootDoc(candidate.root()),
            continuation.apply(candidate.root(), segments)
        ));
    }

    /**
     * Reports whether {@code root} is a TRIVIAL RECEIVER for the trivial-receiver first-selector attach in
     * {@link #chainFanOut}: a bare {@code NameExpr}, {@code FieldAccessExpr}, {@code this}, or {@code super}, and NOT a
     * type-like/factory qualifier ({@link MethodCallChainSourcePlanner#promotesFirstCall} — an uppercase name or type
     * {@code FieldAccessExpr}, whose first call is a factory invocation folded onto the root line by the factory-root seam
     * above). Method-call and object-creation roots are excluded by construction (they are not one of these kinds).
     *
     * <p>Keyed only on the root's AST kind — no width, no source-shape signal — so the attach verdict is a fixpoint. A
     * width- or source-conditioned "first selector attaches" would flip between passes; a pure structural key does not.
     */
    private boolean chainRootIsTrivialReceiver(Expression root) {
        if (promotesFirstCall.test(root)) {
            return false;
        }
        return root.isNameExpr()
            || root instanceof FieldAccessExpr
            || root.isThisExpr()
            || root.isSuperExpr();
    }

    /**
     * Reports whether {@code expression}'s chain root is a {@linkplain #chainRootIsTrivialReceiver(Expression) trivial
     * receiver} — the case in which {@link #chainFanOut} keeps the first selector on the root's opening line. The lambda-body
     * arrow seam ({@code LambdaExpressionPrinter.lambdaBodyChainArrowBestFitting}) asks this to keep a trivial-receiver body
     * ANCHORED on the {@code ->} line rather than breaking after the arrow: {@code dispatchJob -> orderEvent.validateOrder()}
     * ⏎{@code .deliveryPlan()}…, matching the attach the method-call-argument opener path already produces for the same
     * chain. Keyed only on the root's AST kind, so the arrow verdict stays the same structural fixpoint as the fan itself.
     */
    boolean chainRootIsTrivialReceiver(MethodCallExpr expression) {
        return chainRootIsTrivialReceiver(methodCallChainAnalysis.apply(expression).root());
    }

    /**
     * Reports whether the first selector of a trivial-receiver chain is safe to glue to the root's opening line in
     * {@link #chainFanOut}: it has NO type arguments, NO comment of any kind, and either NO call arguments or ONLY simple
     * leaf arguments (a name, field access, {@code this}/{@code super}, or literal). Such a selector renders as one flat,
     * non-breaking {@code .selector(...)} token through {@link #attachedFirstSelectorSegment}, so it is byte-identical
     * whether it sits on the root line or its own continuation line, and attaching it is a fixpoint.
     *
     * <p>A first selector with a lambda, nested-call, or multi-argument list can break INTERNALLY, and the inner break's
     * indentation is relative to the segment's live column — which shifts when the attach moves the selector onto the root
     * line — so it must NOT attach (it keeps the fan-from-first shape, where its argument list breaks at a stable
     * continuation column). This is a purely structural test on the selector's arguments, no width, so the attach verdict
     * stays deterministic.
     *
     * <p>The comment-free requirement is what lets {@link #attachedFirstSelectorSegment} render the selector as bare text:
     * the fan otherwise threads a selector's leading / name / between-selector trailing comments through the shared segment
     * renderer, and a bare-text attach would drop them. A first selector carrying a comment therefore stays on the
     * fan-from-first shape (its comment placed by the shared renderer); the attach only claims a comment-free leaf selector.
     * The chain's remaining trailing line comments (on the fanned tail, e.g. {@code .streamArn() // note}) are unaffected —
     * they sit on selectors {@code chainFanOut} still routes through the shared segment renderer.
     */
    private boolean firstSelectorAttachesSafely(MethodCallExpr firstSelector) {
        return firstSelectorAttachPreconditionsMet(firstSelector)
            && firstSelector.getArguments().stream().allMatch(this::isSimpleLeafArgument);
    }

    /**
     * Extends {@link #firstSelectorAttachesSafely} to a NON-LEAF first selector ({@code .stream("input",
     * Consumed.as("source"))}) whose flat, unbroken compact form still fits at the deterministic attached column (the
     * root's rendered column plus the flat text length) — {@link RootLineWidth#measure} on {@code firstSelector} reads
     * that column directly because a chain's first selector shares its root's range start. Both inputs are AST-derived
     * and column-derived, never source-shape, so the fit verdict is identical on every pass.
     *
     * <p>Still requires the leaf-selector preconditions ({@link #firstSelectorAttachPreconditionsMet} — no type
     * arguments, no comment) and additionally excludes any lambda argument, which has its own dedicated attach
     * ({@link #bareNameReceiverFirstSelectorHugsLambda}) and cannot render as atomic flat text without collapsing its
     * body's line breaks. A selector that does not fit flat here falls through to the fan-from-first shape unchanged —
     * this never introduces a new over-width line, it only widens which selectors attach.
     */
    private boolean firstSelectorAttachesFlat(MethodCallExpr firstSelector, LayoutContext layout) {
        if (!firstSelectorAttachPreconditionsMet(firstSelector)) {
            return false;
        }
        if (firstSelector.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)) {
            return false;
        }
        String flatText = compactSource.compactFlat(firstSelector);
        return rootLineWidth.measure(firstSelector, flatText, layout) <= options.lineWidth();
    }

    // Shared leaf/flat-attach preconditions: no type arguments, no comment anywhere on the selector (own, contained, or a
    // trailing line comment), so an attach can always render the selector as bare atomic text without dropping a comment.
    private boolean firstSelectorAttachPreconditionsMet(MethodCallExpr firstSelector) {
        if (firstSelector.getTypeArguments().isPresent()) {
            return false;
        }
        if (methodCallSegmentHasComment.test(firstSelector) || sourceShapePolicy.hasContainedComments(firstSelector)) {
            return false;
        }
        return finalTrailingLineComments.apply(firstSelector).isEmpty();
    }

    /**
     * Renders a trivial-receiver chain's attach-safe first selector — leaf-argument ({@link #firstSelectorAttachesSafely})
     * or flat-fitting non-leaf ({@link #firstSelectorAttachesFlat}) — as a single, SOURCE-NEUTRAL {@code .selector(arg, …)}
     * token that never breaks at a selector boundary, glued to the root's opening line in {@link #chainFanOut}. Either gate
     * guarantees a comment-free selector, so the compact join is its complete rendering and it can never open its own
     * broken argument list.
     *
     * <p>This deliberately does NOT go through {@code methodCallChainSegment}: that shared renderer would take its
     * {@code sourceMultilineMethodCallSegmentArguments} branch and break the selector's argument list whenever the author
     * wrote the arguments across source lines — a source-shape signal. When attaching the selector pushes the opening line
     * over width (a long root prefix, e.g. a cast-wrapped initializer {@code (List<Foo>) fluentTemplate.to("…")}), that
     * source-driven break becomes VISIBLE and flips across passes: the flat re-format then keeps the selector inline while
     * the original multiline source broke it (the salesforce {@code CompositeApiCollectionsManualIT} oscillation). Emitting
     * one source-neutral {@code Doc.text} makes the attached selector's shape a pure function of the AST, so both passes
     * render it identically
     * even when the opening line is unavoidably over width — matching google-java-format, which attaches regardless of
     * width and never re-breaks such a leaf selector.
     */
    private Doc attachedFirstSelectorSegment(MethodCallExpr firstSelector) {
        return Doc.text(
            "." + firstSelector.getNameAsString()
                + "(" + compactSource.compactJoin(firstSelector.getArguments()) + ")"
        );
    }

    private boolean isSimpleLeafArgument(Expression argument) {
        return argument.isNameExpr()
            || argument.isFieldAccessExpr()
            || argument.isThisExpr()
            || argument.isSuperExpr()
            || argument.isLiteralExpr();
    }

    /**
     * Renders the promoted factory call ({@code Type.factory(a, b)}) that heads a canonical-fan factory-root chain, matching
     * the root doc {@code MethodCallChainSourcePlanner.plan} produces when it promotes the first call of a static/factory
     * root. A zero/one-argument factory call renders through {@code groupedPromotedMethodCall}
     * ({@code Doc.group(Type + softChainContinuation(.factory()))}, which keeps {@code Type.factory()} on one line when it
     * fits and splits to {@code Type}⏎{@code .factory()} only when the column forces it).
     *
     * <p>The canonical-fan multi-argument factory-root convergence. A multi-argument factory
     * call renders SOURCE-NEUTRALLY as a width-driven {@link Doc#group} of its argument list ({@code Type.factory(} then
     * each argument, the {@code )} glued to the last), so the {@code DocRenderer} keeps the arguments flat when they fit at
     * the promoted root's live column and breaks them one per line only on genuine overflow. Rendering the argument-list
     * group directly makes the arguments' break the renderer's width verdict at the true column on every pass — a fixpoint
     * by construction, even for a promoted factory call whose scope spans source lines
     * ({@code StreamSupport}⏎{@code .stream(a, b)}).
     *
     * <p>The factory call reaching here carries a lambda only when {@link #expressionLambdaFactoryCallPromotesFlat} admitted
     * it — a compact expression lambda in a call whose whole flat form fits, so the width-driven group keeps it flat exactly
     * like the imperative promotion; block-lambda factory calls stay withheld by the {@code chainFanOut} gate. Otherwise its
     * arguments are plain expressions the shared {@code methodCallArgumentList} renders without a hug/opener decision.
     * Comment-carrying argument lists keep the source-shape rendering ({@code expressionRenderer.format}) — the width-driven
     * group would not preserve an inter-argument comment — but such a factory call would already have been withheld from the
     * fan upstream, so this only guards the residual.
     */
    private Doc promotedFactoryRootDoc(MethodCallExpr factoryCall) {
        if (
            factoryCall.getArguments().size() == 1
            && factoryCall.getArgument(0) instanceof MethodCallExpr chainArgument
            && !sourceShapePolicy.hasContainedComments(factoryCall)
        ) {
            Optional<Doc> singleChainArg = singleChainArgFactoryRootDoc(factoryCall, chainArgument);
            if (singleChainArg.isPresent()) {
                return singleChainArg.orElseThrow();
            }
        }
        if (factoryCall.getArguments().isEmpty() && !sourceShapePolicy.hasContainedComments(factoryCall)) {
            return zeroArgFactoryRootDoc(factoryCall);
        }
        if (factoryCall.getArguments().size() <= 1) {
            return groupedPromotedMethodCall.apply(factoryCall);
        }
        if (sourceShapePolicy.hasContainedComments(factoryCall)) {
            return expressionRenderer.format(factoryCall, LayoutContext.root());
        }
        return multiArgFactoryRootDoc(factoryCall);
    }

    // The canonical-fan single-argument-chain factory-root convergence. A one-argument
    // promoted factory call whose sole argument is itself a fan-threshold chain ({@code Optional.of(a.b().c().d())},
    // {@code Arrays.stream(a.b().c())}) renders SOURCE-NEUTRALLY as a width-driven {@link Doc#group}: {@code Type.factory(}
    // glued on the root line, the argument fan under one continuation indent, the {@code )} dedented. This replaces
    // {@code groupedPromotedMethodCall}, whose {@code softChainContinuation} group instead splits the SCOPE off the
    // factory name ({@code Optional}⏎{@code .of(...)}) when the whole promoted call overflows, and whose
    // {@code sourceMultilineArguments} branch keeps the name attached ({@code Optional.of(}⏎{@code ...}) once the source
    // argument spans lines — the flat-vs-split flip that IS the {@code ExpectLeaderAction} / {@code LogManagerTest} /
    // {@code DescribeConsumerGroupTest} residual: on the flat-source pass the early canonical-fan route reaches this
    // through {@code chainFanOut} and {@code groupedPromotedMethodCall} splits the name off; on the re-format the factory
    // argument now spans lines, {@code chainHasSourceMultilineArguments} is true, the early route is skipped, and the
    // imperative plan tail keeps the name attached. Rendering the argument fan as a width-driven group here makes the
    // {@code Type.factory(} opener the renderer's width verdict at the true column on every pass — the name stays glued
    // and only the argument fan breaks — a fixpoint by construction, matching the multi-argument arm below.
    //
    // The fan is built ONCE, prefix-agnostic ({@code canonicalFanChain(argument, "", root())}), so the argument's root
    // renders at {@link LayoutContext#root()} and cannot re-flip against the opener. Withheld when the argument is not a
    // fannable chain ({@code canonicalFanChain} empty — a non-fan call, an object-creation or comment/lambda carrier),
    // when the factory call carries its own comments, or for the zero-argument case, all of which fall through to
    // {@code groupedPromotedMethodCall}'s established shapes.
    private Optional<Doc> singleChainArgFactoryRootDoc(MethodCallExpr factoryCall, MethodCallExpr chainArgument) {
        Optional<Doc> fan = canonicalFanChain(chainArgument, "", LayoutContext.root());
        if (fan.isPresent()) {
            String prefix = methodCallPrefix.apply(factoryCall);
            Doc fanDoc = fan.orElseThrow();
            return Optional.of(Doc.group(
                Doc.concat(
                    Doc.text(prefix + "("),
                    Doc.indent(Doc.concat(Doc.SOFT_LINE, fanDoc)),
                    Doc.SOFT_LINE,
                    Doc.text(")")
                )
            ));
        }
        return Optional.empty();
    }

    // A ZERO-ARGUMENT promoted factory call ({@code CacheFactory.newBuilder()}) renders as
    // ATOMIC text ({@code Type.factory()}) rather than through {@code groupedPromotedMethodCall}, whose
    // {@code softChainContinuation} group splits the type off the selector ({@code CacheFactory}⏎{@code .newBuilder()})
    // when the whole factory root does not fit at its live column. That split is exactly what the field-chain
    // initializer showed: on the attached arm ({@code NAME = CacheFactory.newBuilder()…} at a deep declaration column)
    // the group could not keep {@code CacheFactory.newBuilder()} together, so it broke the type off the selector, and
    // that arm won the initializer {@code bestFitting} on line count. Rendering the factory root atomically makes the
    // arm's FIRST LINE ({@code NAME = CacheFactory.newBuilder()}) overflow when the type + selector do not fit at the
    // attach column, so the renderer's fit gate — not a soft-line split — drops the attached arm and the
    // break-after-{@code =} arm wins, keeping {@code CacheFactory.newBuilder()} together on the continuation line. It is
    // a pure function of the AST, so both passes render the identical atomic text and the verdict is a fixpoint. This is
    // the google-java-format / prettier-java convention that a type qualifier never splits from its first call. A short
    // LHS (the {@code Flux.usingWhen(…)} case) keeps the factory root ATTACHED because the atomic opener
    // fits there — the fit gate attaches when there is space and only breaks after {@code =} when there is not.
    private Doc zeroArgFactoryRootDoc(MethodCallExpr factoryCall) {
        return Doc.text(methodCallPrefix.apply(factoryCall) + "()");
    }

    private Doc multiArgFactoryRootDoc(MethodCallExpr factoryCall) {
        String prefix = methodCallPrefix.apply(factoryCall);
        return Doc.group(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.SOFT_LINE,
                        methodCallArgumentList.render(prefix, factoryCall.getArguments(), Doc.LINE)
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * The canonical-fan expression-lambda factory-root convergence. Reports whether a promoted
     * factory call carrying a compact EXPRESSION lambda ({@code IntStream.iterate(50, n -> n + 7)}) may fold onto the root
     * line through {@link #chainFanOut}'s factory promotion. Without this the {@code chainFanOut} gate withholds every
     * lambda-carrying first call, so the flat-source pass fans the chain with {@code IntStream} on its own line
     * ({@code IntStream}⏎{@code .iterate(...)}⏎{@code .limit(...)}) while the imperative plan tail (which promotes a
     * static/factory first call regardless of its lambda) keeps {@code IntStream.iterate(...)} folded on the re-format — the
     * {@code UnifiedLogTest} residual.
     *
     * <p>Scoped so the promotion is a fixpoint: the factory call must have NO block lambda (block-lambda hugs stay on the
     * deferred lambda-arrow seam), its lambda arguments must be single-line expression lambdas with no contained comments,
     * and the WHOLE compact factory call must fit on one line. Under those conditions {@link #promotedFactoryRootDoc} renders
     * it as a width-driven group that stays flat (the arguments fit), reproducing the imperative flat promotion byte for byte
     * — the promotion never introduces a lambda-body break, so both passes converge. A factory call whose flat form overflows
     * is withheld (returns false) and keeps the pre-seam split shape, so this only claims the compact case that actually
     * oscillates.
     */
    private boolean expressionLambdaFactoryCallPromotesFlat(MethodCallExpr factoryCall) {
        if (
            methodCallSegmentHasBlockLambdaArgument.test(factoryCall)
            || sourceShapePolicy.hasContainedComments(factoryCall)
        ) {
            return false;
        }
        boolean lambdasAreCompactExpressionBodies = factoryCall.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .allMatch(lambda -> lambda.getExpressionBody().isPresent()
                    && !sourceShapePolicy.hasContainedComments(lambda));
        return lambdasAreCompactExpressionBodies
            && rootLineWidth.measure(factoryCall, compactSource.compact(factoryCall), LayoutContext.root()) <= options.lineWidth();
    }

    /**
     * Reports whether a promoted factory call carrying an expression lambda may fold onto the root line through
     * {@link #chainFanOut}'s factory promotion because it has TWO OR MORE arguments, so {@link #promotedFactoryRootDoc}
     * renders it through the width-driven MULTI-ARGUMENT
     * {@link Doc#group} ({@code Flux.usingWhen(} on the root line, each argument fanned one per line, {@code )} dedented)
     * rather than through {@code groupedPromotedMethodCall}'s source-shape-sensitive single-argument lambda-hug branches.
     * This keeps the factory root ({@code Type.factory(}) glued to the root line and only fans its argument list for a
     * lambda-carrying factory call whose whole flat form overflows (so {@link #expressionLambdaFactoryCallPromotesFlat}
     * declines it).
     *
     * <p>Scoped so the fold is a fixpoint: no block lambda (block-lambda hugs stay on the deferred lambda-arrow seam), no
     * contained comments (the width-driven group would not preserve an inter-argument comment), TWO OR MORE arguments (a
     * single expression-lambda argument routes through {@code groupedPromotedMethodCall}'s source-gated hug and is held
     * back), and every lambda argument an expression-body lambda whose own body is comment-free — so the argument list is a
     * pure width-driven decision at the render column. A single-line-flat factory call is already claimed by
     * {@link #expressionLambdaFactoryCallPromotesFlat}; this only adds the over-width multi-argument case that would
     * otherwise fan the factory root onto its own line ({@code Flux}⏎{@code .usingWhen(…)}).
     */
    private boolean expressionLambdaFactoryCallFoldsAsMultiArgGroup(MethodCallExpr factoryCall) {
        if (
            factoryCall.getArguments().size() < 2
            || methodCallSegmentHasBlockLambdaArgument.test(factoryCall)
            || sourceShapePolicy.hasContainedComments(factoryCall)
        ) {
            return false;
        }
        return factoryCall.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .allMatch(lambda -> lambda.getExpressionBody().isPresent()
                    && !sourceShapePolicy.hasContainedComments(lambda));
    }

    /**
     * Renders the object-creation root ({@code new Type(a, b, c, d)}) that heads a canonical-fan constructor-root chain
     * SOURCE-NEUTRALLY, the object-creation analogue of {@link #promotedFactoryRootDoc}. The constructor arguments render
     * through a width-driven {@link Doc#group} ({@code new Type(} then each argument, the {@code )} glued to the last), so
     * the {@code DocRenderer} keeps them flat when they fit at the root's live column and breaks them one per line only on
     * genuine overflow.
     *
     * <p>The canonical-fan constructor-root convergence — the object-creation analogue of the
     * factory-root {@code source-multiline-method-root-chain-initializer} oscillation. {@code chainFanOut} rebuilds the
     * root once per pass. Without rendering the group directly, an object-creation root would reach
     * {@code expressionRenderer.format(root, root())} via {@code chainFanOut} but {@code brokenObjectCreationRenderer} via
     * the imperative fall-through, and those two paths disagree for a multi-segment constructor-rooted chain whose
     * non-final SELECTOR arguments break across source lines between passes
     * ({@code new EndpointFactory(alpha, beta, gamma, delta).generate(…, Instance.builder()…build()).blockFirst(…)}): on
     * the flat-selector pass {@code chainHasSourceMultilineArguments} is false, the early canonical-fan route fires, and
     * {@code chainFanOut} renders the root through {@code expressionRenderer.format} → {@code ObjectCreationPrinter}'s
     * width-driven {@code Doc.group} (flat when the constructor line fits); on the re-format the {@code .generate(…)}
     * arguments span lines, {@code chainHasSourceMultilineArguments} is true, the early route is skipped, and the
     * imperative fall-through renders the root through {@code brokenObjectCreationRenderer}, whose {@code forceBreak}
     * argument shape always puts each constructor argument on its own line — so a constructor line that fits would flip
     * flat↔broken forever. Rendering the argument-list group directly here (bypassing {@code ObjectCreationPrinter}'s
     * {@code sourceMultilineArguments} preservation as well) makes the arguments' break the renderer's width verdict at the
     * true column on every pass, a fixpoint by construction, and lets the fall-through route the object-creation root
     * through the same {@code chainFanOut} builder the flat-selector pass reaches.
     *
     * <p>The object-creation reaching here is a comment-free, non-anonymous, non-empty-argument constructor (the fan gate
     * withholds anonymous bodies, block/expression lambdas, and comment-bearing chains), so its arguments are plain
     * expressions the shared {@code methodCallArgumentList} renders without a hug/opener decision. Empty-argument and
     * comment-carrying constructors fall back to {@code widthDrivenObjectCreation}'s own guard (which routes them to the
     * force-broken form); such constructors are withheld from this path by the {@link #chainFanOut} object-creation-root
     * gate, so this only guards the residual.
     *
     * <p>Rendering delegates to {@code ObjectCreationPrinter.widthDrivenObjectCreation}, which builds the exact
     * {@code breakableArguments::argument} + {@code Doc.joinComma} group {@code ObjectCreationPrinter} produces for a
     * source-single-line constructor — byte-identical to the {@code expressionRenderer.format(objectCreation, root())} doc
     * the flat-selector pass already reached, but with the {@code sourceMultilineArguments} preservation bypassed so a
     * source-multiline argument list that fits collapses to flat on every pass rather than flipping.
     */
    private Doc promotedObjectCreationRootDoc(ObjectCreationExpr objectCreation) {
        return widthDrivenObjectCreationRenderer.apply(objectCreation);
    }

    /**
     * Reports whether {@code chainFanOut} may render an object-creation root through the source-neutral width-driven
     * {@link #promotedObjectCreationRootDoc}. Scoped to the constructor-root-of-a-fan-threshold-chain position: a
     * non-anonymous, comment-free, non-empty-argument {@link ObjectCreationExpr}. Anonymous bodies own their own layout
     * after the header and keep {@code expressionRenderer.format}; empty-argument and comment-carrying constructors have no
     * width-driven argument decision (or would drop an inter-argument comment) and keep the existing rendering. This gate
     * mirrors the factory-root gate {@code promotesFirstCall && !block/expression-lambda}: the width-driven group only
     * fires where the arguments are plain expressions whose break is purely the renderer's column verdict.
     */
    boolean objectCreationRootIsWidthDrivenFanEligible(Expression root) {
        return root instanceof ObjectCreationExpr objectCreation
            && objectCreation.getAnonymousClassBody().isEmpty()
            && !objectCreation.getArguments().isEmpty()
            && !sourceShapePolicy.hasContainedComments(objectCreation);
    }

    /**
     * Reports whether a chain root is a parenthesized (or parenthesized-cast) expression wrapping a fan-threshold method-call
     * chain — {@code ((OffsetFetchRequestData) res.unsentRequests.get(0).requestBuilder().build().data())}. Such a root
     * renders across multiple lines (its inner chain fans by the canonical rule), so its closing {@code )} lands on a
     * continuation line; the chain's first selector fans onto its own dotted line rather than attaching there, a
     * source-neutral verdict independent of how the author wrote the source. Keyed strictly on an enclosed/cast root whose
     * inner chain fans ({@code chainFansByCanonicalRule}); a parenthesized non-chain receiver ({@code (a + b).foo()})
     * renders on one line and keeps its established attach.
     */
    boolean rootIsEnclosedFanningChain(Expression root) {
        if (!(root instanceof EnclosedExpr enclosed)) {
            return false;
        }
        Expression inner = enclosed.getInner();
        if (inner instanceof CastExpr cast) {
            inner = cast.getExpression();
        }
        return inner instanceof MethodCallExpr innerChain && chainFansByCanonicalRule(innerChain);
    }

    /**
     * Re-enters the chain printer's shared multi-argument list renderer ({@code MethodCallPrinter.methodCallArgumentList}):
     * the promoted factory root builds its own width-driven {@link Doc#group} but hands the argument list itself to this
     * back-edge so the arguments render exactly as the ordinary call path would.
     */
    @FunctionalInterface
    interface MethodCallArgumentList {
        Doc render(String prefix, NodeList<Expression> arguments, Doc line);
    }

    /**
     * Measures the chain root's opening line at the rendered column ({@code MethodCallChainPrinter.rootLineWidth}): the
     * expression-lambda factory-root promotion probes whether the whole compact factory call fits before folding it onto
     * the root line. It stays with the caller because the chain's other width gates share the same reconstruction.
     */
    @FunctionalInterface
    interface RootLineWidth {
        int measure(Expression root, String text, LayoutContext layout);
    }
}
