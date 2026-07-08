package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders method-call chains after {@link MethodCallPrinter} has identified a call-shaped expression.
 *
 * <p>This helper owns chain analysis, source-shaped chain preservation, root promotion, final segment suffixes, and
 * chain comments. The boundary keeps ordinary method-call argument dispatch in {@link MethodCallPrinter} while making
 * dotted-chain layout a separate state machine instead of another branch inside the call dispatcher.
 */
final class MethodCallChainPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final RawSource rawSource;

    private final SourceShapePolicy sourceShapePolicy;

    private final MethodCallChainSourcePlanner methodChainPlanner;

    private final FormatterOptions options;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final MethodCallPrinter calls;

    private final TypePrinter types;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final Function<ObjectCreationExpr, Doc> widthDrivenObjectCreationRenderer;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;

    private final BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments;

    private final ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan;

    private final LayoutDecisionLog layoutDecisions;

    private final SourceMultilineLambdaCallLayout sourceMultilineLambdaCalls;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> expressionLambdaMethodCallBodyOpener;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> expressionLambdaLogicalBinaryBodyOpenerHug;

    MethodCallChainPrinter(
            JavaFormatContext context,
            MethodCallPrinter calls,
            TypePrinter types,
            CommentedExpressionListPrinter commentedExpressionLists,
            JavaFormatRule<Expression> expressionRenderer,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            Function<ObjectCreationExpr, Doc> widthDrivenObjectCreationRenderer,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments,
            ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan,
            Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody,
            Function<LambdaExpr, String> lambdaParameters,
            BiFunction<String, MethodCallExpr, Optional<Doc>> expressionLambdaMethodCallBodyOpener,
            BiFunction<String, MethodCallExpr, Optional<Doc>> expressionLambdaLogicalBinaryBodyOpenerHug
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.rawSource = context.rawSource;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.options = context.options;
        this.compactSource = context.compactSource;
        this.layoutWidth = context.layoutWidth;
        this.methodChainPlanner = new MethodCallChainSourcePlanner(context, lineWidth(LayoutWidth.LineBudget.CURRENT));
        this.calls = calls;
        this.types = types;
        this.commentedExpressionLists = commentedExpressionLists;
        this.expressionRenderer = expressionRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.widthDrivenObjectCreationRenderer = widthDrivenObjectCreationRenderer;
        this.objectCreationPrefix = objectCreationPrefix;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.huggableBlockLambdaFirstLine = huggableBlockLambdaFirstLine;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.expressionLambdaArgumentPlan = expressionLambdaArgumentPlan;
        this.layoutDecisions = context.layoutDecisions;
        this.lambdaParameters = lambdaParameters;
        this.huggedGapCommentedLambdaBody = huggedGapCommentedLambdaBody;
        this.expressionLambdaMethodCallBodyOpener = expressionLambdaMethodCallBodyOpener;
        this.expressionLambdaLogicalBinaryBodyOpenerHug = expressionLambdaLogicalBinaryBodyOpenerHug;
        this.sourceMultilineLambdaCalls = new SourceMultilineLambdaCallLayout(
            context.sourceShapePolicy,
            node -> expressionRenderer.format(node, LayoutContext.root()),
            lambdaParameters,
            calls::methodCallPrefix,
            this::methodCallSegmentPrefixText,
            calls::methodCallArgumentList
        );
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, LayoutContext.root());
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression, LayoutContext layout) {
        return methodCallChain(expression, MethodCallBreakMode.AUTO, layout);
    }

    /**
     * Canonical-fan cutover seam (End-state A): routes a fan-threshold, comment/lambda-free chain straight to the
     * source-neutral {@link #chainFanOut} builder, <em>independent of the author's source shape</em>, and returns empty
     * for every other chain so the caller keeps its existing decision tree.
     *
     * <p>This is the shared, multi-caller sibling of the two source-neutral fan routes already inside
     * {@link #methodCallChain}: the AUTO stay-flat-gate route (which fans a fitting fan-threshold chain) and the early
     * canonical-fan route (which fans a breaking one). Both of those gate on {@code !sourceMultilineArguments}, so a
     * caller reaching {@code methodCallChain} in {@code FORCED} mode on a pass whose inner-selector arguments span source
     * lines ({@code sourceMultilineArguments == true}) skips them and lands on the source-shape-sensitive imperative
     * ladder below — {@code canAttachFirstSegmentToSimpleRoot} in particular folds the first selector onto a simple
     * receiver root ({@code parser.accepts(...)}) when the source chain was multiline and root+selector started on the
     * same source line. Its already-fanned re-format then has single-line arguments, {@code sourceMultilineArguments}
     * flips to {@code false}, the early route fires, and {@code chainFanOut} splits the first selector onto its own line
     * ({@code parser}⏎{@code .accepts(...)}). The two passes disagree forever. Emitting the {@code chainFanOut} shape here
     * — the same shape both {@code sourceMultilineArguments} passes must converge on — before the caller can consult
     * source shape removes that dependence: {@code chainFanOut} is a pure function of the AST, so both passes rebuild the
     * identical fan (a fixpoint by construction, the argument the landed single-segment rankers and the initializer /
     * factory-root cutover seams already rely on).
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
     * The fan-position break rules, resolved first-match-wins. Stage 0 of the reprint-by-default break-rule model
     * ({@code docs/proposals/reprint-by-default-break-rules.md}) hosts exactly one — the End-state A canonical fan — so
     * this registry answers the same question {@link #canonicalFanChain} asked inline: fan the chain when the canonical
     * rule admits it, otherwise (no match &rarr; {@link Optional#empty()}) leave it to the imperative cascade the caller
     * falls back to. The chain-shaped {@link ChainFanRequest} candidate carries the caller-appended final-segment suffix
     * so the general {@link BreakRule}/{@link BreakRuleRegistry} abstraction hosts the chain without a leaky node-level
     * signature. The remaining fan sub-shapes inside {@link #chainFanOut} (factory-root fold, single-selector,
     * trivial-receiver attach, fanned selectors) are the Stage-1 extraction targets.
     */
    private final BreakRuleRegistry<ChainFanRequest> chainFanRules = BreakRuleRegistry.of(List.of(
        BreakRule.of(
            "canonical-fan",
            request -> chainFansByCanonicalRuleAdmittingTrailingComment(request.expression()),
            this::canonicalFanLayout
        )
    ));

    /**
     * The candidate handed to the fan-position break rules: the chain expression plus the caller-appended final-segment
     * suffix and positional context that {@link #canonicalFanLayout} needs to build the fan.
     */
    private record ChainFanRequest(MethodCallExpr expression, String finalSegmentSuffix, LayoutContext layout) {}

    /**
     * Builds the source-neutral fan {@link Doc} for a chain the canonical rule admits — the layout of the
     * {@code canonical-fan} {@link BreakRule}. Lifted verbatim from the former {@link #canonicalFanChain} body, so the
     * fan Doc and its {@code --explain} width-break recording are byte-identical.
     */
    private Doc canonicalFanLayout(ChainFanRequest request) {
        MethodCallExpr expression = request.expression();
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        // Record the width-break for {@code --explain} exactly as the in-{@code methodCallChain} early canonical-fan route
        // does before it fans (`:748`): a chain fanned here that overflows its rendered line is a width-driven break, and the
        // explain report must attribute it as "method chain … flat width … > N available … segments, one per line" rather
        // than dropping to a bare rule-driven break. {@code recordChainWidthBreak} self-gates on {@code flatWidth > lineWidth},
        // so a chain fanned purely by the link-count/root-kind rule while it still fits records nothing (it is not a width
        // break). The budget is read from the caller's {@link LayoutContext}, matching the {@code lineBudget} the early route
        // threads.
        recordChainWidthBreak(expression, analysis, request.layout().widthBudget());
        return chainFanOut(
            analysis.root(),
            analysis.calls(),
            MethodCallChainTail.of(request.finalSegmentSuffix()),
            request.layout()
        );
    }

    /**
     * Reports whether a chain is one {@link #canonicalFanChain} would fan: the End-state A structural rule fires
     * ({@code chainBreaksByRule}) and none of the carve-outs apply (own/contained comments, block-lambda arguments,
     * commented or expression-lambda selectors, or an attachable expression-lambda body). This is the exact gate
     * {@link #canonicalFanChain} applies before it emits {@code chainFanOut}, factored out so a caller can ask the
     * question without rendering the fan.
     *
     * <p>The binary/ternary-operand seam (U8) uses this: when a broken binary argument's {@code flat} rendering already
     * fans a chain operand through this rule (via the dispatched {@code chainFanOut}), the argument printer must not also
     * offer the operand-per-line {@code broken} alternative, because the {@code flat}-vs-{@code broken} choice is gated on
     * the source-shape {@code wasMultiline} signal and would otherwise flip the operand between the fanned and flat shapes
     * across passes (the U8 non-idempotence). Reusing this single predicate keeps the carve-outs — comment / block-lambda /
     * expression-lambda-body chains, the deferred lambda-arrow seam — identical to what {@code canonicalFanChain} withholds.
     */
    boolean chainFansByCanonicalRule(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()) {
            return false;
        }
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        return chainBreaksByRule(analysis)
            && !analysis.hasComments()
            && !analysis.hasBlockLambdaArgument()
            && analysis.calls().stream().noneMatch(this::methodCallSegmentHasComment);
        // Canonical-fan cutover seam (End-state A): the expression-lambda-selector withhold
        // ({@code noneMatch(methodCallSegmentHasExpressionLambdaArgument)}) and its source-multiline
        // attach sibling ({@code !sourceMultilineLambdaChainPlan(analysis).canAttachAnyExpressionLambdaBody()})
        // are REMOVED. They were load-bearing only because {@link #methodCallChainSegment} rendered a selector's
        // expression-lambda body through source-shape-gated paths, so a fanned expr-lambda chain's segment width flipped
        // across passes and any enclosing {@code bestFitting}/attach flipped with it. Now that
        // {@code sourceNeutralExpressionLambdaSegment} renders that selector as a pure function of the AST (a
        // conditional group of flat vs. hug/fan), the fan is idempotent, so these chains ({@code stream.map(x -> x.foo())
        // .collect(...)}) may fan like every other chain. {@code canAttachAnyExpressionLambdaBody} was additionally a
        // source-shape signal ({@code sourceMultilineLambdaChainPlan}); keeping it would have re-coupled the fan verdict to
        // the author's line breaks. Comment / block-lambda chains stay withheld above.
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
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        return chainBreaksByRule(analysis)
            && !analysis.hasBlockLambdaArgument()
            && analysis.calls().stream().noneMatch(this::methodCallSegmentHasComment)
            && chainCommentsAreOnlyTrailingLine(analysis);
    }

    /**
     * Reports whether a chain's ONLY comment is a single trailing line comment on its LAST selector
     * ({@code .streams()}⏎{@code .get(0)}⏎{@code .streamArn(); // XXX}) — the one comment shape the source-neutral
     * {@link #chainFanOut} provably preserves without a placement hazard, because {@link #methodCallChainSegments} re-emits
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
     * it through {@code chainFanOut} (attaching the first selector, End-state A Rule 1), while the broken re-format reads
     * the comment on the selector, withholds the fan, and drops to the source-shape imperative ladder that fans from the
     * first selector — {@code streamsListResult.streams()} ⇄ {@code streamsListResult}⏎{@code .streams()} forever. Letting
     * the fan claim this one shape routes BOTH passes through the same source-neutral {@code chainFanOut}, so the placement
     * flip no longer selects divergent layouts (the camel {@code ShardIteratorHandler} / {@code CsvDataFormat} /
     * {@code DefaultSupervisingRouteController} / {@code ExportBaseCommand} residuals).
     */
    private boolean chainCommentsAreOnlyTrailingLine(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        List<MethodCallExpr> calls = analysis.calls();
        return analysis.hasTrailingLineComments()
            && !analysis.rootHasComments()
            && !rootHasTrailingLineCommentBeforeFirstSegment(analysis.root(), calls)
            && calls.stream().noneMatch(this::methodCallSegmentHasComment)
            && chainTrailingLineCommentOnlyOnLastCall(calls);
    }

    /**
     * Reports that the chain carries a trailing line comment on its LAST selector and on NO earlier selector, so the only
     * trailing comment sits in the placement-stable after-last-selector slot {@link #methodCallChainSegments} re-emits (see
     * {@link #chainCommentsAreOnlyTrailingLine}). A comment in a between-selector gap or on the first (promoted/attached)
     * call fails this, keeping the chain on the comment-preserving imperative path.
     */
    private boolean chainTrailingLineCommentOnlyOnLastCall(List<MethodCallExpr> calls) {
        if (calls.isEmpty() || finalTrailingLineComments(calls.getLast()).isEmpty()) {
            return false;
        }
        for (int index = 0; index + 1 < calls.size(); index++) {
            if (!trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
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
     * position, so the flip no longer selects the imperative fan-from-first shape on the pass that sees the comment. A
     * comment-free fan is excluded here (it is not the flip case) and keeps its existing position-specific routing
     * untouched; a chain with any non-trailing comment is already excluded by {@code chainFansByCanonicalRule}.
     */
    boolean chainFansByCanonicalRuleWithTrailingLineComment(MethodCallExpr expression) {
        return methodCallChainHasFinalTrailingLineComment(expression)
            && chainFansByCanonicalRuleAdmittingTrailingComment(expression);
    }

    /**
     * Reports whether {@code expression} is a binary or ternary expression that contains a flattened operand which the
     * End-state A canonical-fan rule fans ({@link #chainFansByCanonicalRule}). Such an expression's dispatched flat
     * rendering hard-breaks that operand into a source-neutral {@code chainFanOut}, so any caller that would otherwise
     * offer a source-shape-gated operand-per-line broken alternative must instead commit the flat (chain-fanned) shape —
     * it is the AST-pure fixpoint the two passes converge on.
     *
     * <p>Canonical-fan cutover seam (End-state A), the binary/logical/string-concat OPERAND carrier (the "G bucket").
     * This is the shared carve-out gate for every binary-argument / binary-initializer decider whose flat arm already
     * fans a chain operand: {@link BreakableArgumentExpressionPrinter} (U8) first used the equivalent recursion on the
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
     * The lambda-body position (U7) of the canonical-fan cutover: reports whether a chain that IS an expression-lambda
     * body should fan by the End-state A rule ({@link #chainFansByCanonicalRule}), <em>and</em> its root is one the
     * lambda-body fan renders idempotently.
     *
     * <p>The lambda-body fan hugs the chain root on the lambda-header line and fans the selectors below it, rendering
     * through {@code huggedLambdaBodyChain} → {@code forcedMethodCallChain} with the header threaded as
     * {@link LayoutContext#leftEdgePrefix()}. That path re-renders the chain root through {@code chainFanOut} at
     * {@link LayoutContext#root()} (column zero) regardless of the header's real column — fine for a root whose rendering
     * is column-invariant (a bare {@code NameExpr}/{@code FieldAccessExpr}/{@code this} receiver, or an unscoped bare call
     * whose flat form is atomic), but NOT for an {@link ObjectCreationExpr} root: {@code new X()} hugs its first selector
     * on a flat-source pass and breaks onto its own line on a source-multiline pass, so a {@code new X().setA(...).setB(...)}
     * lambda body fanned here oscillates between {@code new X().setA(} and {@code new X()}⏎{@code .setA(} forever (the
     * kafka {@code Endpoints}/{@code ProduceResponse} {@code .map(x -> new Record()....)} shapes). Object-creation-rooted
     * lambda-body chains are therefore withheld from the fan and left on the packed / opener-breaking shapes below, which
     * are already source-shape-stable for them; they remain the deferred slice of this cutover (the nested-root gap the
     * chain-path-unification plan calls out for {@code chainFanOut} rendering a non-name root at {@code root()}).
     *
     * <p>Also withheld: a body-chain whose lambda is the argument of a <em>chain-selector</em> call
     * ({@code stream.filter(e -> e.getKey().description().contains(...))}). When the outer chain fans, that
     * {@code .filter(...)} selector is re-rendered by the chain printer's own segment path rather than reaching the
     * lambda-hug seam, so the body's fan-vs-pack verdict is owned by two different code paths across passes and flips
     * (the kafka {@code SelectorTest} shape). Restricting the fan to lambdas hosted by a call with a non-call scope (a
     * statement-level or receiver-rooted call such as {@code verifier.assertEachRoute(h -> …)}) keeps the hug column and
     * its owner stable. This is a conservative withhold — it only ever removes a fan, never forces one — so it cannot
     * introduce a new oscillation.
     */
    boolean lambdaBodyChainFansByCanonicalRule(MethodCallExpr expression) {
        return chainFansByCanonicalRule(expression)
            && !(methodCallChainAnalysis(expression).root() instanceof ObjectCreationExpr)
            && !lambdaBodyHostedByChainSelector(expression);
    }

    /**
     * Reports whether the expression-lambda whose body is {@code bodyChain} is itself an argument of a method call that is
     * a selector in a longer chain (its scope is another {@code MethodCallExpr}), e.g. the {@code .filter(...)} in
     * {@code stream.filter(e -> bodyChain)}. Such a hosting call reindents when the outer chain fans, moving the lambda
     * body's rendered column and its layout owner between passes, so the lambda-body canonical fan is withheld there.
     */
    private boolean lambdaBodyHostedByChainSelector(MethodCallExpr bodyChain) {
        // Withhold when the lambda whose body is {@code bodyChain} is an argument of a method call that is itself a chain
        // selector (its scope is another method call): the outer chain reindents that {@code .filter(e -> …)} host when it
        // fans, moving the body's rendered column and its layout owner between passes. The body chain sits under its lambda
        // through a statement/parenthesis wrapper ({@code bodyChain → ExpressionStmt → LambdaExpr}), so walk up to the
        // nearest enclosing {@link LambdaExpr} before inspecting the hosting call.
        return enclosingLambda(bodyChain)
                .flatMap(Node::getParentNode)
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .flatMap(MethodCallExpr::getScope)
                .filter(MethodCallExpr.class::isInstance)
                .isPresent();
    }

    private Optional<LambdaExpr> enclosingLambda(MethodCallExpr bodyChain) {
        Optional<Node> current = bodyChain.getParentNode();
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof LambdaExpr lambdaExpr) {
                return Optional.of(lambdaExpr);
            }
            if (node instanceof MethodCallExpr || node instanceof ObjectCreationExpr) {
                // Reached an enclosing call before any lambda — this chain is not a lambda body in the current subtree.
                return Optional.empty();
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return forcedMethodCallChain(expression, LayoutWidth.LineBudget.CURRENT);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression, LayoutWidth.LineBudget lineBudget) {
        return forcedMethodCallChain(expression, lineBudget, LayoutContext.root());
    }

    // LDM-2f (#190): the layout-carrying entry seam. A caller that shares its first line with a fixed prefix (the return
    // chain threads {@code layout.withLeftEdgePrefix("return ")}) hands that context through here so the chain width gates
    // can attribute the prefix at the rendered column. The no-{@code layout} overload above passes {@code root()} (empty
    // prefix), keeping every other forced-chain caller byte-identical until its own activation slice.
    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return forcedMethodCallChain(expression, lineBudget, lineWidth(lineBudget), layout);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return forcedMethodCallChain(expression, firstLineWidth, LayoutContext.root());
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return forcedMethodCallChain(expression, LayoutWidth.LineBudget.CURRENT, firstLineWidth, layout);
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
                lineWidth(LayoutWidth.LineBudget.CONTINUATION),
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
        if (!methodChainPlanner.rootIsObjectCreation(expression)) {
            return false;
        }
        return methodCallChainAnalysis(expression).calls().size() >= 2;
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
        Optional<String> root = compactMethodCallChainRoot(expression, segments);
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
            chainContinuation(Doc.join(Doc.HARD_LINE, chain.remainingSegments().stream().map(Doc::text).toList()))
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
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if (
            analysis.hasComments()
            || analysis.hasBlockLambdaArgument()
            || !(analysis.root() instanceof ObjectCreationExpr objectCreation)
            || analysis.calls().isEmpty()
            || objectCreation.getAnonymousClassBody().isPresent()
            || sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation)
            || analysis.calls().stream().anyMatch(call -> !compactMethodCallChainSegmentCanStayFlat(call))
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
            // compact-tail fan-out in objectRootSingleSegmentChain is a return-chain-only refinement, and this
            // broken-object-creation path stays byte-identical.
            return Optional.of(objectRootSingleSegmentChain(
                objectCreation,
                rootDoc,
                calls.getFirst(),
                MethodCallChainTail.EMPTY,
                MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION,
                analysis.sourceMultilineChain(),
                LayoutWidth.LineBudget.CURRENT,
                firstLineWidth,
                LayoutContext.root()
            ));
        }
        // Multi-segment constructor chains break one segment per line through the shared chain machinery, which decides
        // whether the constructor root stays compact or breaks its own argument list, then lays every .call() on its own
        // continuation line. This is the same path name-rooted chains use, so the root-alone one-per-line shape and all
        // its comment/width handling stay consistent across root kinds.
        return forcedMethodCallChain(expression, firstLineWidth);
    }

    private record PackedMethodCallChainText(String firstLine, List<String> remainingSegments) {
        PackedMethodCallChainText {
            remainingSegments = List.copyOf(remainingSegments);
        }
    }

    private record SourceMultilineLambdaChainPlan(
        boolean rootCanAttachExpressionLambdaBody,
        List<Boolean> callCanAttachExpressionLambdaBody,
        Optional<SourceMultilineLambdaCallLayout.AttachedFirstSegment> firstCall
    ) {
        SourceMultilineLambdaChainPlan {
            callCanAttachExpressionLambdaBody = List.copyOf(callCanAttachExpressionLambdaBody);
        }

        boolean callCanAttachExpressionLambdaBody(int index) {
            return index >= 0
                && index < callCanAttachExpressionLambdaBody.size()
                && callCanAttachExpressionLambdaBody.get(index);
        }

        boolean anyNonFinalCallCanAttachExpressionLambdaBody() {
            for (int index = 0; index < Math.max(0, callCanAttachExpressionLambdaBody.size() - 1); index++) {
                if (callCanAttachExpressionLambdaBody.get(index)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reports whether the root or any selector in this chain has a source-multiline expression-lambda body that could
         * hug its call opener. The canonical-fan cutover ({@link #canonicalFanChain}) withholds any such chain so the
         * lambda-hug↔break shape stays with the deferred lambda-arrow seam rather than being flattened into the fan.
         */
        boolean canAttachAnyExpressionLambdaBody() {
            return rootCanAttachExpressionLambdaBody || callCanAttachExpressionLambdaBody.stream().anyMatch(Boolean::booleanValue);
        }
    }

    private Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            MethodCallBreakMode.FORCED,
            MethodCallChainTail.EMPTY,
            lineBudget,
            firstLineWidth,
            layout
        );
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        return compactRootWithBrokenFinalChainSegment(expression, LayoutWidth.LineBudget.CURRENT);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return compactRootWithBrokenFinalChainSegment(expression, lineBudget, LayoutContext.root());
    }

    // LDM-2f (#190): the layout-carrying entry seam for the compact-root-with-broken-final-segment shape. The return chain
    // threads {@code layout.withLeftEdgePrefix("return ")} through here so {@code compactRootLineWidth} can attribute the
    // {@code return } prefix at the rendered column. The no-{@code layout} overload above passes {@code root()} (empty
    // prefix), keeping every other caller byte-identical until its own activation slice.
    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodChainPlanner.methodCallChainRoot(expression, calls);
        SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(root, calls);
        Optional<Doc> sourceMultilineFirstExpressionLambda = comments.speculatively(
            () -> sourceMultilineFirstExpressionLambdaChain(
                expression,
                root,
                calls,
                MethodCallChainTail.EMPTY,
                sourceMultilineLambdaPlan
            )
        );
        if (sourceMultilineFirstExpressionLambda.isPresent()) {
            return sourceMultilineFirstExpressionLambda;
        }
        if (
            !calls.isEmpty()
            && methodCallChainIsSourceMultiline(expression)
            && methodCallSegmentHasExpressionLambdaArgument(calls.getLast())
        ) {
            return Optional.empty();
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && methodCallSegmentHasExpressionLambdaArgument(methodRoot)
        ) {
            return Optional.empty();
        }
        if (
            calls.stream()
                    .limit(Math.max(0, calls.size() - 1))
                    .anyMatch(this::methodCallSegmentHasExpressionLambdaArgument)
        ) {
            return Optional.empty();
        }
        if (root instanceof MethodCallExpr methodRoot && calls.size() == 1) {
            return compactRootWithBrokenFinalSegment(methodRoot, calls.getFirst(), lineBudget, layout);
        }
        if (methodChainPlanner.promotesFirstCall(root) && calls.size() == 2) {
            return compactRootWithBrokenFinalSegment(calls.getFirst(), calls.get(1), lineBudget, layout);
        }
        return Optional.empty();
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

    private Optional<String> compactMethodCallChainRoot(MethodCallExpr expression, List<String> segments) {
        if (!compactMethodCallChainSegmentCanStayFlat(expression)) {
            return Optional.empty();
        }
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodCallScope) {
            Optional<String> root = compactMethodCallChainRoot(methodCallScope, segments);
            root.ifPresent(ignored -> segments.add(compactMethodCallChainSegment(expression)));
            return root;
        }
        if (!scoped.getAllContainedComments().isEmpty() || sourceShapePolicy.wasMultiline(scoped)) {
            return Optional.empty();
        }
        segments.add(compactMethodCallChainSegment(expression));
        return Optional.of(compactSource.compact(scoped));
    }

    Optional<String> compactMethodCallChainRoot(MethodCallExpr expression) {
        return compactMethodCallChainRoot(expression, new ArrayList<>());
    }

    private boolean compactMethodCallChainSegmentCanStayFlat(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .noneMatch(argument -> argument instanceof LambdaExpr
                        || !argument.getAllContainedComments().isEmpty()
                        || sourceShapePolicy.wasMultiline(argument)
                );
    }

    private String compactMethodCallChainSegment(MethodCallExpr expression) {
        return "."
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString()
            + "("
            + compactSource.compactJoin(expression.getArguments())
            + ")";
    }

    private int expressionLambdaBodyLineWidth(String line) {
        return layoutWidth.line(LayoutWidth.LineBudget.BLOCK, options.indentUnit() + line);
    }

    private int packedExpressionLambdaBodyLineWidth(String line) {
        return layoutWidth.line(LayoutWidth.LineBudget.BLOCK, options.indentUnit().repeat(3) + line);
    }

    /**
     * Preserves an already-multiline call statement when the argument list itself spans source lines.
     *
     * <p>Statement rendering still owns the trailing semicolon, but the call printer owns the call-shape decision because
     * it already owns argument breaks and source-multiline argument layout.
     */
    Optional<Doc> sourceMultilineMethodCallStatement(
            MethodCallExpr expression,
            ExpressionStmt statement
    ) {
        if (!sourceShapePolicy.wasMultiline(statement)) {
            return Optional.empty();
        }
        return calls.sourceMultilineArguments(expression);
    }

    /**
     * Prints a dotted call chain when the call is naturally chain-shaped or when a caller forces the chain break.
     *
     * <p>Auto mode leaves short uncommented calls alone. Forced mode is used by return, assignment, statement, and field
     * contexts that already know the surrounding line overflowed and need a broken call shape.
     */
    Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        return methodCallChain(expression, MethodCallBreakMode.fromForced(force), LayoutContext.root());
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, "", LayoutWidth.LineBudget.CURRENT, layout);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            breakMode,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT,
            layout
        );
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, MethodCallChainTail.of(finalSegmentSuffix), lineBudget, layout);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            breakMode,
            MethodCallChainTail.of(finalSegmentSuffix),
            lineBudget,
            firstLineWidth,
            layout
        );
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, finalSegmentSuffix, LayoutWidth.LineBudget.CURRENT, layout);
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, finalSegmentSuffix, lineBudget, lineWidth(lineBudget), layout);
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        boolean rootObjectCreationNeedsBreak = methodChainPlanner.rootObjectCreationNeedsBreak(analysis);
        SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(analysis);
        boolean sourceMultilineArguments = chainHasSourceMultilineArguments(analysis, sourceMultilineLambdaPlan);
        if (
            !breakMode.isForced()
            && finalBlockLambdaSegmentCanStayCompact(expression, lineBudget)
        ) {
            return Optional.empty();
        }
        if (
            (!breakMode.isForced()
                && !analysis.hasComments()
                && !analysis.hasBlockLambdaArgument()
                && !analysis.sourceMultilineChain()
                // D2a comment-safety residue (hub-canonicalization-atomic-rewrite.md, residue "A"): a chain carrying an
                // inter-segment `//` line comment must not stay flat, so its fan-only comment-preserving render survives
                // the eventual `selectorBrokeAfter` retirement. Redundant alongside the live read TODAY — a `//` there
                // forces the selector onto a later source line, so `sourceMultilineChain` above is already `true` and
                // this conjunct is a strict subset that never flips the gate (byte-identical). See
                // {@link #chainHasInterSegmentLineComment}.
                && !analysis.hasInterSegmentLineComment()
                // SPIKE fanA (canonical fan, End-state A): a chain that reaches its link-count/root-kind threshold
                // MUST fan one selector per line even when the flat form fits, so it does not stay flat here. This is
                // #163's structural stay-flat-gate edit (`!chainBreaksByRule`) — but unlike #163 the break is then
                // routed to the source-neutral `chainFanOut` builder (see the early canonical-fan route below), not the
                // source-shape-sensitive imperative ladder #163 left downstream.
                && !chainBreaksByRule(analysis)
                && !sourceMultilineArguments
                && !rootObjectCreationNeedsBreak
                // The stay-flat probe must measure the chain at the same line position it will actually occupy. When the
                // chain shares its line with a prefix (an assignment target plus operator, an initializer name, etc.) the
                // caller threads that prefix through {@code firstLineWidth}; measuring with a prefix-blind width here would
                // keep a chain flat whose real line overflows. {@code firstLineWidth} defaults to {@code lineWidth(lineBudget)},
                // so prefix-less callers stay byte-identical to the old {@code layoutWidth.line(...)} probe.
                //
                // The same channel now also carries NESTING DEPTH: a chain rendered as a wrapped call argument or a
                // nested initializer (e.g. {@code RetryPlan.create(...).toRetry()} as the argument of {@code .retryWhen(...)})
                // sits at its enclosing argument list's continuation indentation, deeper than the {@code CURRENT} budget
                // the AUTO entry assumes. The argument-list caller threads that deeper budget ({@code CONTINUATION}) as
                // {@code lineBudget}, so {@code firstLineWidth} here measures the chain at its real column and breaks a
                // chain whose flat line only fits the shallow budget but overflows where it actually renders.
                && firstLineWidth.applyAsInt(compactSource.compact(expression)) <= options.lineWidth())
            || expression.getScope().isEmpty()
        ) {
            return Optional.empty();
        }
        Expression root = analysis.root();
        List<MethodCallExpr> calls = analysis.calls();
        if (
            calls.isEmpty()
            || (calls.size() < 2
                && !(root instanceof MethodCallExpr)
                && !forcedSingleCallPrefixOverflows(breakMode, expression, lineBudget)
                && !(breakMode.isForced() && root instanceof ObjectCreationExpr)
                && !rootObjectCreationNeedsBreak
                && !analysis.sourceMultilineChain()
                && !analysis.rootHasComments()
                && !analysis.singleCommentedSegment())
        ) {
            return Optional.empty();
        }
        if (singleStringLiteralCallWithSourceMultilineArguments(root, calls)) {
            return Optional.empty();
        }
        // SPIKE fanA (canonical fan, End-state A). Route a fan-threshold chain straight to the source-neutral
        // `chainFanOut` builder rather than the source-shape-sensitive imperative ladder below. This is the whole
        // premise vs #163: #163 flipped the same stay-flat gate (`!chainBreaksByRule`) but kept the imperative tail —
        // compactRootWithBrokenFinalSegment, the sourceMultilineArguments branches, objectRootSingleSegmentChain, etc. —
        // all of which read the AUTHOR'S source shape. A chain #163 forced to break then got RE-SHAPED by one of those
        // branches, and on pass 2 the now-different source shape selected a different branch: the 432->782 oscillation.
        // chainFanOut is a pure function of the AST (root + each selector on its own dotted line, root rendered through
        // ordinary expression dispatch), so pass 2 sees the identical AST and rebuilds the identical fan — idempotent by
        // construction. Gated comment-free / block-lambda-free: chainFanOut re-renders the root once, and a
        // comment-bearing root re-render would double-claim its comments (the same guard the landed single-segment
        // rankers use for their chainFanOut arm). Comment/lambda chains fall through to the unchanged imperative ladder.
        if (
            chainBreaksByRule(analysis)
            && !analysis.hasComments()
            && !analysis.hasBlockLambdaArgument()
            && !sourceMultilineArguments
            && calls.stream().noneMatch(this::methodCallSegmentHasComment)
        ) {
            recordChainWidthBreak(expression, analysis, lineBudget);
            return Optional.of(chainFanOut(root, calls, finalSegmentSuffix, layout));
        }
        Optional<Doc> flatHeadHuggedFinalLambda = comments.speculatively(
            () -> flatHeadHuggedCommentLambdaChain(expression, analysis, finalSegmentSuffix)
        );
        if (flatHeadHuggedFinalLambda.isPresent()) {
            return flatHeadHuggedFinalLambda;
        }
        if (canBreakAfterCompactExpressionLambdaRoot(breakMode, root, calls, sourceMultilineLambdaPlan, layout)) {
            return Optional.of(
                Doc.concat(
                    Doc.text(compactSource.compact(root)),
                    chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                )
            );
        }
        if (
            breakMode.isForced()
            && calls.size() == 1
            && root.getAllContainedComments().isEmpty()
            && calls.getFirst().getAllContainedComments().isEmpty()
            && !methodCallSegmentHasComment(calls.getFirst())
            && !analysis.rootHasBlockLambdaArgument()
        ) {
            Expression probeRoot = root;
            List<MethodCallExpr> probeCalls = calls;
            Optional<Doc> compactRootWithBrokenSegment = comments.speculatively(
                () -> compactRootWithBrokenFinalSegment(
                    probeRoot,
                    probeCalls.getFirst(),
                    finalSegmentSuffix,
                    lineBudget,
                    layout
                )
            );
            if (compactRootWithBrokenSegment.isPresent()) {
                return compactRootWithBrokenSegment;
            }
        }
        if (calls.size() == 1 && root instanceof MethodCallExpr methodRoot) {
            Doc rootDoc = singleSegmentMethodRootDoc(methodRoot);
            Doc rootTrailingComment = rootTrailingLineCommentBeforeFirstSegment(methodRoot, calls);
            if (rootTrailingComment != Doc.EMPTY) {
                rootDoc = Doc.concat(rootDoc, Doc.lineSuffix(Doc.concat(Doc.text(" "), rootTrailingComment)));
            }
            // A leading line comment on the only segment ({@code lookup(a)} then {@code // c1} on its own line then
            // {@code .orElseThrow(x)}) must own its own continuation line so the comment stays above the segment selector.
            // Attaching such a segment to the root close glued the comment onto the root's closing parenthesis
            // ({@code lookup(a)// c1}); a scope-rooted chain already avoids this because its segments go one-per-line, so
            // route the single-segment case the same way once the segment carries a leading comment.
            if (methodCallSegmentHasLeadingLineComment(calls.getFirst())) {
                return Optional.of(
                    Doc.concat(
                        rootDoc,
                        chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    )
                );
            }
            // A block comment parked in the gap between a method-call root and its only selector
            // ({@code create() /* doc *}{@code / .seal()}) is missed by the stay-flat gate's contained-comment scan, so
            // the chain reaches this single-segment branch. Gluing the segment to the root close
            // ({@code methodCallChainSegmentAttachedToRootClose}) renders it flat and drops the source space before the
            // comment ({@code create()/* doc *}{@code / .seal()}). Route it through the breaking continuation instead, the
            // same escape the leading-line-comment case uses, so the selector's own segment prefix re-emits the comment
            // with its space on its own continuation line. Other single-segment method roots keep the attached-flat shape.
            if (methodCallSegmentHasLeadingGapBlockComment(methodRoot, calls.getFirst())) {
                return Optional.of(
                    Doc.concat(
                        rootDoc,
                        chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    )
                );
            }
            return Optional.of(
                Doc.concat(
                    rootDoc,
                    methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineBudget)
                )
            );
        }
        if (
            root instanceof FieldAccessExpr fieldAccess
            && fieldAccess.getScope() instanceof MethodCallExpr methodRoot
            && calls.size() == 1
        ) {
            return Optional.of(
                Doc.concat(
                    expressionRenderer.format(methodRoot, LayoutContext.root()),
                    chainContinuation(
                        appendFinalSegmentSuffix(
                            fieldAccessMethodCallSegment(fieldAccess, calls.getFirst()),
                            finalSegmentSuffix
                        )
                    )
                )
            );
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan = methodChainPlanner.plan(
            analysis,
            breakMode.isForced()
        );
        chainPlan = promoteFirstBlockLambdaCallWithLambdaBodyComments(analysis, chainPlan).orElse(chainPlan);
        root = chainPlan.root();
        calls = chainPlan.calls();
        sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(root, calls);
        Doc rootDoc = methodCallChainRootDoc(chainPlan, firstLineWidth, layout);
        // Track whether {@code rootDoc} is still the plain {@code expressionRenderer.format(root, root())} doc — the exact
        // root {@link #chainFanOut} rebuilds — so the multi-segment fall-through below can route through the shared fan-out
        // builder byte-identically only in that case. It holds only for an EXPRESSION_RENDERER root that did not fall to the
        // broken-method-call shape; a promoted/grouped/broken-object-creation root, a first-segment-attached root, or a
        // root-trailing-comment-wrapped root produces a different {@code rootDoc} and stays on the inline construction.
        //
        // The comment-free gate is load-bearing: the fall-through routing through {@code chainFanOut} re-renders the root a
        // second time (the {@code rootDoc} built here is discarded in that path), and re-rendering a comment-bearing root
        // would re-claim its already-{@code printed} comments and trip the strict-claims guardrail — the same reason the
        // landed single-segment rankers gate their {@code chainFanOut} arm comment-free. A comment-free root re-renders to a
        // byte-identical {@code Doc}; a comment-bearing chain keeps the unchanged inline construction (rendered once).
        boolean rootDocIsPlainExpressionRenderRoot =
            chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            && !analysis.hasComments()
            && !expressionRenderedChainRootBreaksMethodCall(chainPlan.root(), firstLineWidth);
        boolean firstSegmentAttachedToRoot = false;
        Expression sourceMultilineProbeRoot = root;
        List<MethodCallExpr> sourceMultilineProbeCalls = calls;
        SourceMultilineLambdaChainPlan sourceMultilineProbePlan = sourceMultilineLambdaPlan;
        Optional<Doc> sourceMultilineFirstExpressionLambda = comments.speculatively(
            () -> sourceMultilineFirstExpressionLambdaChain(
                expression,
                sourceMultilineProbeRoot,
                sourceMultilineProbeCalls,
                finalSegmentSuffix,
                sourceMultilineProbePlan
            )
        );
        if (sourceMultilineFirstExpressionLambda.isPresent()) {
            return sourceMultilineFirstExpressionLambda;
        }
        if (canAttachFirstSegmentToSimpleRoot(expression, chainPlan, calls, analysis)) {
            MethodCallExpr firstCall = calls.getFirst();
            root = firstCall;
            calls = new ArrayList<>(calls.subList(1, calls.size()));
            rootDoc = firstSegmentAttachedToSimpleRootDoc(
                chainPlan.root(),
                firstCall,
                sourceMultilineLambdaPlan.firstCall()
            );
            firstSegmentAttachedToRoot = true;
            // The first segment is now glued onto the root, so {@code rootDoc} is the attached-root shape, not the plain
            // expression-renderer root chainFanOut would build; keep this chain on the inline construction.
            rootDocIsPlainExpressionRenderRoot = false;
        }
        if (calls.isEmpty()) {
            return Optional.of(appendFinalSegmentSuffix(rootDoc, finalSegmentSuffix));
        }
        if (
            !analysis.sourceMultilineChain()
            && root instanceof MethodCallExpr methodRoot
            && calls.size() == 1
            && root.getAllContainedComments().isEmpty()
            && !methodCallSegmentHasComment(calls.getFirst())
            && methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
            && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                    .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                    .isPresent()
        ) {
            return Optional.empty();
        }
        Doc rootTrailingComment = rootTrailingLineCommentBeforeFirstSegment(root, calls);
        if (rootTrailingComment != Doc.EMPTY) {
            if (
                root instanceof ObjectCreationExpr objectCreation
                && chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            ) {
                rootDoc = brokenObjectCreationRenderer.apply(objectCreation);
            }
            rootDoc = Doc.concat(rootDoc, Doc.lineSuffix(Doc.concat(Doc.text(" "), rootTrailingComment)));
            // The root now carries a trailing line comment suffix, so {@code rootDoc} is no longer the plain
            // expression-renderer root; a multi-segment chain reaching the fall-through below stays on the inline
            // construction so the comment suffix is preserved.
            rootDocIsPlainExpressionRenderRoot = false;
            if (calls.size() == 1) {
                return Optional.of(
                    Doc.concat(
                        rootDoc,
                        chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    )
                );
            }
        }
        if (canKeepSuffixAttachedToPromotedBlockLambdaRoot(chainPlan, root, calls, finalSegmentSuffix)) {
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        if (
            root instanceof ObjectCreationExpr objectCreation
            && calls.size() == 1
        ) {
            // LDM-3g (#210): rank the compact-with-broken-segment shape against the one-segment-per-line fan-out and let
            // the renderer keep whichever wraps least at the real output column, rather than committing to a shape via the
            // fixed-column firstLineWidth probe inside objectRootSingleSegmentChain below. Only width-driven, comment-free,
            // source-compact-constructor chains reach the ranker; it defers back to the imperative tail otherwise.
            Optional<Doc> rankedObjectRootSegment = rankedObjectRootSingleSegmentChain(
                objectCreation,
                calls.getFirst(),
                finalSegmentSuffix,
                rootDoc,
                chainPlan.rootRendering(),
                analysis,
                lineBudget,
                layout
            );
            if (rankedObjectRootSegment.isPresent()) {
                return rankedObjectRootSegment;
            }
            return Optional.of(objectRootSingleSegmentChain(
                objectCreation,
                rootDoc,
                calls.getFirst(),
                finalSegmentSuffix,
                chainPlan.rootRendering(),
                analysis.sourceMultilineChain(),
                lineBudget,
                firstLineWidth,
                layout
            ));
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && calls.size() == 1
            && !firstSegmentAttachedToRoot
            && methodRootCanKeepSingleSuffixAttached(methodRoot)
            && methodCallSegmentHasNoOwnContainedComments(calls.getFirst())
            && !methodCallSegmentHasComment(calls.getFirst())
        ) {
            if (
                methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
                && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                        .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                        .isPresent()
            ) {
                return Optional.empty();
            }
            if (
                chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.INLINE_PROMOTED_METHOD_CALL
                && promotedNoArgRootScopeOverflows(methodRoot, firstLineWidth)
            ) {
                return Optional.of(
                    Doc.concat(rootDoc, chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)))
                );
            }
            MethodCallExpr probeCall = calls.getFirst();
            if (
                !analysis.sourceMultilineChain()
                && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(calls.getFirst())
            ) {
                Optional<Doc> compactRootWithBrokenSegment = comments.speculatively(
                    () -> compactRootWithBrokenFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget, layout)
                );
                if (compactRootWithBrokenSegment.isPresent()) {
                    return compactRootWithBrokenSegment;
                }
            }
            Optional<Doc> expressionLambdaRoot = comments.speculatively(
                () -> expressionLambdaRootWithSingleSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget, layout)
            );
            if (expressionLambdaRoot.isPresent()) {
                return expressionLambdaRoot;
            }
            // LDM-3 (B8/D16): when the final segment carries breakable arguments the compact-with-broken-segment shape and
            // the one-segment-per-line fan-out are both legal broken layouts that differ in rendered line count, so rank
            // them with a single Doc.bestFitting and let the renderer keep whichever wraps least at the real output column,
            // rather than committing to a shape via the fixed-column LayoutWidth probe below. Only width-driven,
            // comment-free, non-source-shaped chains reach the ranker (promotion, source-multiline, and every
            // comment-guarded branch already returned above); it defers back to this imperative tail otherwise.
            Optional<Doc> rankedSingleSegment = rankedSingleSegmentChain(
                methodRoot,
                probeCall,
                finalSegmentSuffix,
                chainPlan.rootRendering(),
                analysis,
                lineBudget,
                layout
            );
            if (rankedSingleSegment.isPresent()) {
                return rankedSingleSegment;
            }
            if (compactRootFinalSegmentLineOverflows(
                    methodRoot,
                    calls.getFirst(),
                    finalSegmentSuffix,
                    lineBudget,
                    layout
                )) {
                Optional<Doc> compactRootWithBrokenSegment = comments.speculatively(
                    () -> compactRootWithBrokenFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget, layout)
                );
                if (compactRootWithBrokenSegment.isPresent()) {
                    return compactRootWithBrokenSegment;
                }
                // The full chain (compact root plus the attached final segment) overflows at this line position, but the
                // final segment has no arguments to break (e.g. {@code .toRetry()}/{@code .build()}), so the previous
                // helper found nothing to wrap. When the root itself carries breakable arguments, break the root's
                // argument list instead and glue the segment to its close: {@code Type.create(}\n args \n{@code ).toRetry()}.
                // This is the same shape a source-multiline root already produces below; here it is reached for a flat
                // source root that only overflows because it renders at a deep nesting column (a wrapped call argument or
                // nested initializer), the column the caller threads through {@code lineBudget}/{@code firstLineWidth}.
                Optional<Doc> brokenRootWithAttachedSegment = comments.speculatively(
                    () -> brokenRootWithAttachedFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget, layout)
                );
                if (brokenRootWithAttachedSegment.isPresent()) {
                    return brokenRootWithAttachedSegment;
                }
            }
            Optional<Doc> sourceMultilineRoot =
                comments.speculatively(() -> this.calls.sourceMultilineArguments(methodRoot));
            if (sourceMultilineRoot.isPresent()) {
                return Optional.of(
                    Doc.concat(
                        sourceMultilineRoot.orElseThrow(),
                        methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineBudget)
                    )
                );
            }
            if (
                chainPlan.rootRendering()
                    == MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
            ) {
                if (methodCallSegmentHasBlockLambdaArgument(methodRoot)) {
                    return Optional.of(
                        Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    );
                }
                return Optional.of(
                    groupedPromotedRootWithSingleSegment(root, rootDoc, calls.getFirst(), finalSegmentSuffix, layout)
                );
            }
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        // Record the width break only here, where the printer has committed to the broken one-segment-per-line chain
        // this method's PrinterWrap describes. The earlier deferral branches hand rendering to a different printer that
        // does not lay the chain out one per line, so recording before them could attribute a "N segments, one per line"
        // layout to a path that never produced it.
        recordChainWidthBreak(expression, analysis, lineBudget);
        // chain-unify U1 (#190): the multi-segment fall-through builds the exact one-segment-per-line fan-out
        // {@code chainFanOut} produces — root then each selector on its own dotted continuation line
        // ({@code Doc.concat(root, chainContinuation(root, methodCallChainSegments(calls, tail)))}) — so route it through
        // the shared source-neutral builder rather than reconstructing that shape inline, consolidating the fan-out onto a
        // single named, reusable arm the ranked engine can list in later slices. This is byte-identical only when
        // {@code rootDoc} is still the plain {@code expressionRenderer.format(root, root())} doc chainFanOut rebuilds; a
        // promoted/grouped/broken-object-creation root, a first-segment-attached root, or a root-trailing-comment-wrapped
        // root produces a different {@code rootDoc}, so those keep the inline construction.
        if (rootDocIsPlainExpressionRenderRoot) {
            return Optional.of(chainFanOut(root, calls, finalSegmentSuffix, layout));
        }
        // Object-creation root cutover seam (End-state A): a comment-free, non-anonymous, non-empty-argument
        // constructor-rooted fan-threshold chain ({@code new EndpointFactory(a, b, c, d).generate(…).blockFirst(…)}) whose
        // planner rendering is {@code BROKEN_OBJECT_CREATION} routes through the shared {@code chainFanOut} builder, whose
        // object-creation-root arm renders the constructor arguments through the source-neutral width-driven
        // {@link #promotedObjectCreationRootDoc}. This converges with the flat-selector pass, which already reaches
        // {@code chainFanOut} through the early canonical-fan route: both passes now render the root through the same
        // width-driven group, so a constructor line that fits stays flat on every pass instead of flipping to the
        // {@code brokenObjectCreationRenderer} force-break shape once a non-final selector's arguments span source lines.
        // Comment-free / block-lambda-free is required because {@code chainFanOut} re-renders the root and every selector a
        // second time (discarding {@code rootDoc}); a comment-bearing chain would re-claim its already-printed comments, so
        // it keeps the inline construction below (rendered once). The selectors render identically either way — the fan's
        // multi-segment tail is byte-for-byte the {@code chainContinuation(root, methodCallChainSegments(...))} the inline
        // construction builds — so only the root doc changes.
        if (
            objectCreationRootIsWidthDrivenFanEligible(root)
            && chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION
            && !firstSegmentAttachedToRoot
            && !analysis.hasComments()
            && !analysis.hasBlockLambdaArgument()
            && calls.stream().noneMatch(this::methodCallSegmentHasComment)
        ) {
            return Optional.of(chainFanOut(root, calls, finalSegmentSuffix, layout));
        }
        List<Doc> segments = methodCallChainSegments(calls, finalSegmentSuffix);
        return Optional.of(
            Doc.concat(
                rootDoc,
                chainContinuation(root, segments)
            )
        );
    }

    private boolean singleStringLiteralCallWithSourceMultilineArguments(
            Expression root,
            List<MethodCallExpr> calls
    ) {
        return root instanceof StringLiteralExpr
            && calls.size() == 1
            && calls.getFirst().getAllContainedComments().isEmpty()
            && !methodCallSegmentHasComment(calls.getFirst())
            && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(calls.getFirst());
    }

    /**
     * The canonical-fan structural rule (End-state A) — see {@link MethodCallChainSourcePlanner#chainBreaksByRule} for
     * the link-count/root-kind thresholds, which that planner method owns as the single source of truth. This chain
     * printer and the variable-initializer path (via {@code InitializerChainShape.chainBreaksByRule}) both read the
     * identical verdict, so a fan-threshold chain routes onto the same source-neutral fan without the rule drifting
     * between two copies.
     */
    private boolean chainBreaksByRule(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        return methodChainPlanner.chainBreaksByRule(analysis);
    }

    private boolean methodRootCanKeepSingleSuffixAttached(MethodCallExpr methodRoot) {
        if (methodRoot.getAllContainedComments().isEmpty()) {
            return true;
        }
        if (
            methodCallSegmentHasLineComments(methodRoot)
            && !methodCallSegmentHasLeadingLineComment(methodRoot)
            && !methodCallSegmentHasNameComment(methodRoot)
        ) {
            return true;
        }
        return methodCallSegmentHasBlockLambdaArgument(methodRoot)
            && !methodCallSegmentHasLeadingLineComment(methodRoot)
            && !methodCallSegmentHasNameComment(methodRoot);
    }

    private boolean finalBlockLambdaSegmentCanStayCompact(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        if (!methodCallSegmentHasBlockLambdaArgument(expression) || methodCallSegmentHasComment(expression)) {
            return false;
        }
        String callPrefix = calls.methodCallPrefix(expression);
        return huggableBlockLambdaFirstLine.apply(callPrefix, expression.getArguments())
                .filter(firstLine -> layoutWidth.line(lineBudget, firstLine) <= options.lineWidth())
                .isPresent();
    }

    /**
     * Reports whether a method-call root's compact first line, with the single final segment attached
     * ({@code root.selector(args)…}), overflows — the flat-gate that decides whether the statement/field single-segment
     * chain must break onto the {@link #compactRootWithBrokenFinalSegment} / {@link #brokenRootWithAttachedFinalSegment}
     * broken shapes.
     *
     * <p>D1e (#190) threads {@code layout} so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this flat-gate for the eventual reflow-by-width flip. It is NOT yet consulted: the decision still uses
     * the fixed-budget {@code layoutWidth.line(lineBudget, …)} floor exactly as before, so threading it is byte-identical.
     * The statement/field callers pass their real {@link LayoutContext} (a {@code STATEMENT}/{@code root()} context whose
     * {@code leftEdgePrefix} is empty), matching the sibling {@link #compactRootLineWidth} gate this parameter mirrors.
     */
    private boolean compactRootFinalSegmentLineOverflows(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (call.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)) {
            return false;
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String compactLine = compactSource.compact(methodRoot)
            + "."
            + typeArguments
            + call.getNameAsString()
            + "("
            + methodCallSegmentArgumentsWidthText(call.getArguments())
            + ")"
            + finalSegmentSuffix;
        return layoutWidth.line(lineBudget, compactLine) > options.lineWidth();
    }

    /**
     * Breaks a method-call root's own argument list one argument per line and glues the single final segment to its
     * closing parenthesis: {@code Type.create(}\n args \n{@code ).toRetry()}.
     *
     * <p>Reached when {@link #compactRootFinalSegmentLineOverflows} reports the whole chain over width at its rendered
     * line position but the final segment has no arguments of its own to wrap ({@code .toRetry()}/{@code .build()}). It
     * mirrors the shape a source-multiline root produces, but is reached for a flat source root that overflows only
     * because it renders at a deep nesting column. Returns empty (leaving the existing flat layout) unless the root
     * carries breakable arguments, is not already source-multiline, has no comments, and its opener {@code Type.create(}
     * itself fits at {@code lineBudget}, so the broken shape is only chosen when it is both needed and valid.
     *
     * <p>D1e (#190) threads {@code layout} so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this statement/field single-segment flat-gate for the eventual reflow-by-width flip. It is NOT yet
     * consulted: the opener-fit decision still uses the fixed-budget {@code layoutWidth.line(lineBudget, …)} floor exactly
     * as before, so threading it is byte-identical (the statement/field callers pass an empty-prefix context).
     */
    private Optional<Doc> brokenRootWithAttachedFinalSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            methodRoot.getArguments().isEmpty()
            || !methodRoot.getAllContainedComments().isEmpty()
            || sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)
            || methodCallSegmentHasSourceMultilineBlockLambdaArgument(methodRoot)
            || methodRoot.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)
        ) {
            return Optional.empty();
        }
        if (layoutWidth.line(lineBudget, calls.methodCallPrefix(methodRoot) + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                calls.brokenMethodCall(methodRoot),
                methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineBudget)
            )
        );
    }

    /**
     * LDM-3 (B8/D16): emits one ranked {@link Doc#bestFitting(java.util.List) bestFitting} for a comment-free,
     * width-driven single-segment method-call chain whose final segment carries breakable arguments, replacing the
     * {@link LayoutWidth}-probe gate that hand-picked the broken shape. The two alternatives are ordered flattest-first —
     * (1) the compact shape that keeps the root and selector on one line and breaks only the final segment's argument list
     * ({@code root.selector(}\n args \n{@code )}), and (2) the one-segment-per-line fan-out ({@code root}\n
     * {@code .selector(...)}) — and the renderer keeps whichever wraps the fewest rendered lines at the real output
     * column. This is reached only inside the broken-chain branch (the stay-flat gate already proved the flat form
     * overflows), so a break is required; between the two broken shapes the compact one keeps the root and selector
     * together and therefore wraps at least as few lines as the fan-out, which is the line-count decision the
     * fixed-indent {@code LayoutWidth} probe could not make and the reason the renderer, not a probe, now owns it.
     *
     * <p><strong>Source-shape gates run before ranking.</strong> Returns {@link Optional#empty()} — deferring to the
     * imperative tail below — unless the chain is chosen purely on width: the root renders through ordinary expression
     * dispatch ({@link MethodCallChainSourcePlanner.ChainRootRendering#EXPRESSION_RENDERER}, so promoted / builder /
     * broken-object-creation roots stay imperative), the chain and root arguments were not split across source lines, and
     * the final segment carries breakable, non-lambda arguments. It also defers when {@link #compactRootWithBrokenFinalSegment}
     * cannot build the compact shape (its opener does not fit), because then only the imperative broken-root fallback
     * applies and there is nothing to rank. A deliberately-multiline chain or a promoted root is a source-preserved shape,
     * never a width-ranked alternative, so ranking can never override it.
     *
     * <p><strong>Comment-bearing chains never reach here.</strong> The {@code !analysis.hasComments()} gate keeps them on
     * the imperative ladder, whose {@code comments.speculatively(...)} rollbacks own the first-builder-wins claim; building
     * both alternatives eagerly (as this does) would double-claim comments and trip the strict-claims guardrail.
     */
    private Optional<Doc> rankedSingleSegmentChain(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            || analysis.hasComments()
            || analysis.sourceMultilineChain()
            || sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)
            || methodCallSegmentHasSourceMultilineBlockLambdaArgument(methodRoot)
            || call.getArguments().isEmpty()
            || call.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
            || methodCallSegmentHasBlockLambdaArgument(call)
        ) {
            return Optional.empty();
        }
        // Alternative 1 (flattest): keep the root and the selector on one line, breaking only the final segment's own
        // argument group. compactRootWithBrokenFinalSegment builds the same shape the imperative O5 path does; if its
        // guards reject the chain there is nothing width-driven to rank, so defer to the imperative tail.
        Optional<Doc> compactBrokenSegment =
            compactRootWithBrokenFinalSegment(methodRoot, call, finalSegmentSuffix, lineBudget, layout);
        if (compactBrokenSegment.isEmpty()) {
            return Optional.empty();
        }
        // Alternative 2 (fallback, always legal): the one-segment-per-line fan-out — the root alone, then the selector on
        // its own continuation line. This is the shape Branch P produces for a broken chain; here it competes on line
        // count with the compact shape rather than being reached only after a width probe.
        Doc fanOut = chainFanOut(methodRoot, List.of(call), finalSegmentSuffix, layout);
        return Optional.of(Doc.bestFitting(List.of(compactBrokenSegment.orElseThrow(), fanOut)));
    }

    /**
     * LDM-3g (#210): the object-creation-rooted sibling of {@link #rankedSingleSegmentChain}. Emits one ranked
     * {@link Doc#bestFitting(java.util.List) bestFitting} for a comment-free, width-driven single-segment chain whose root
     * is a source-compact constructor ({@code new Type(args).selector(...)}) and whose final segment carries breakable
     * arguments, replacing the {@link #objectRootSingleSegmentChain} first-line {@code LayoutWidth} probe that hand-picked
     * the broken shape. The two alternatives are ordered flattest-first — (1) the compact shape that keeps the
     * {@code new Type(args)} root and the selector on one line and breaks only the final segment's argument list
     * ({@code new Type(args).selector(}\n args \n{@code )}), reusing the same {@link #compactRootWithBrokenFinalSegment}
     * the method-root ranker uses (it already builds this shape for object-creation roots), and (2) the one-segment-per-line
     * fan-out ({@code new Type(args)}\n{@code .selector(...)}) — and the renderer keeps whichever wraps the fewest rendered
     * lines at the real output column. Each alternative's inner groups still break the constructor's own argument list when
     * the column demands it, so the deeper "constructor args broken too" shape the imperative probe reached at narrow
     * widths remains reachable through alternative (1)/(2)'s own nested breaks; the ranker only owns the compact-versus-
     * fan-out verdict the fixed-column probe could not measure.
     *
     * <p><strong>Same gates as the method-root ranker.</strong> Returns {@link Optional#empty()} — deferring to
     * {@link #objectRootSingleSegmentChain} — unless the chain is chosen purely on width: the root is a compact-source
     * constructor rendered through ordinary expression dispatch
     * ({@link MethodCallChainSourcePlanner.ChainRootRendering#EXPRESSION_RENDERER}, so a multiline constructor promoted to
     * {@link MethodCallChainSourcePlanner.ChainRootRendering#BROKEN_OBJECT_CREATION} stays imperative), the chain and the
     * constructor arguments were not split across source lines, and the final segment carries breakable, non-lambda
     * arguments. A deliberately-multiline chain or a source-broken constructor is a source-preserved shape, never a
     * width-ranked alternative, so ranking can never override it. The {@code !analysis.hasComments()} gate keeps
     * comment-bearing chains on the imperative ladder whose {@code comments.speculatively(...)} rollbacks own the
     * first-builder-wins claim; building both alternatives eagerly (as this does) would double-claim comments.
     */
    private Optional<Doc> rankedObjectRootSingleSegmentChain(
            ObjectCreationExpr objectCreation,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            Doc rootDoc,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            || analysis.hasComments()
            || analysis.sourceMultilineChain()
            || sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation)
            || objectCreation.getAnonymousClassBody().isPresent()
            || !finalSegmentSuffix.isEmpty()
            || call.getArguments().isEmpty()
            || call.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
            || methodCallSegmentHasBlockLambdaArgument(call)
        ) {
            return Optional.empty();
        }
        // Alternative 1 (flattest): keep the constructor and the selector on one line, breaking only the final segment's
        // own argument group. compactRootWithBrokenFinalSegment already handles object-creation roots; if its guards reject
        // the chain (its opener does not fit) there is nothing width-driven to rank, so defer to the imperative tail.
        Optional<Doc> compactBrokenSegment =
            compactRootWithBrokenFinalSegment(objectCreation, call, finalSegmentSuffix, lineBudget, layout);
        if (compactBrokenSegment.isEmpty()) {
            return Optional.empty();
        }
        // Alternative 2 (fallback, always legal): the one-segment-per-line fan-out — the constructor alone, then the
        // selector on its own continuation line. This is the shape objectRootSingleSegmentChain's overflow branch produces;
        // here it competes on line count with the compact shape rather than being reached only after a first-line probe.
        Doc fanOut = chainFanOut(objectCreation, List.of(call), finalSegmentSuffix, layout);
        return Optional.of(Doc.bestFitting(List.of(compactBrokenSegment.orElseThrow(), fanOut)));
    }

    /**
     * Builds the one-segment-per-line fan-out for a chain from the AST alone, so a ranked alternative always exists
     * regardless of source shape or opener fit (convergence-redesign Mechanism 1). The fan-out is the root followed by
     * each selector on its own dotted continuation line ({@code root}⏎{@code .selector(args)}); a single-selector chain
     * fans that lone selector onto its continuation line, a multi-segment chain fans one selector per line.
     *
     * <p>Unlike {@link #forcedMethodCallChain}, this builder never gates on {@code openerFits} or
     * {@code sourceMultilineChain} and never returns empty for a flat single-selector call whose opener fits — the whole
     * point is that the fan-out is a pure function of the AST, present on every input, so the ranked engine sees the same
     * alternative on every pass (this source-neutrality is what dissolves the initializer/return convergence Blocker 1
     * that #191 tracks; slice 3 routes the initializer's collapse arm through this builder).
     *
     * <p>It owns the dot-split skeleton only. Each segment renders through the ordinary {@link #methodCallChainSegment}
     * group, so a segment stays flat when {@code .selector(args)} fits at its continuation column and opens its own
     * argument list only on genuine overflow — the per-segment argument decision stays with the renderer, and the
     * single-simple-argument compact-tail refinement ({@link #refuseOpeningSingleSimpleReturnChainTail}) composes through
     * that same segment renderer rather than being re-implemented here. It builds one {@link Doc} and renders each call
     * exactly once, so it is comment-neutral and never double-claims a comment; callers that emit it as one arm of a
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

    /**
     * The fan SHAPE rules, resolved first-match-wins — the four one-per-line shapes {@link #chainFanOut} chooses among
     * once a chain is being fanned, extracted from its former imperative {@code if} cascade (reprint-by-default Stage 1,
     * {@code docs/proposals/reprint-by-default-break-rules.md}). Declaration order is precedence and reproduces the
     * cascade exactly: the factory-root fold is tried first (it can fold a two-selector chain), then the single-selector
     * fan, then the trivial-receiver first-selector attach, and finally the always-matching fanned-selectors fallback
     * stands in for the cascade's {@code else}. Each rule is a pure function of its {@link ChainFanCandidate} and emits
     * one source-neutral {@link Doc}, and only the winning rule's layout runs, so the extraction is byte-identical.
     */
    private final BreakRuleRegistry<ChainFanCandidate> fanShapeRules = BreakRuleRegistry.of(List.of(
        BreakRule.of("chain-fan-factory-root-fold", this::fanFoldsFactoryRoot, this::fanFactoryRootFoldLayout),
        BreakRule.of("chain-fan-single-selector", candidate -> candidate.calls().size() == 1, this::fanSingleSelectorLayout),
        BreakRule.of(
            "chain-fan-trivial-receiver-attach",
            this::fanAttachesTrivialReceiverFirstSelector,
            this::fanTrivialReceiverAttachLayout
        ),
        BreakRule.of("chain-fan-selectors", candidate -> true, this::fanSelectorsLayout)
    ));

    // Factory / type-like root cutover seam: a {@code promotesFirstCall} root (an uppercase {@code NameExpr} or
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
    // Review round 2 (comment #4, "class + method should not break until there is a space left"). A factory call
    // carrying an expression lambda folds onto the root line in two source-neutral cases: (a) its whole compact form
    // fits flat ({@link #expressionLambdaFactoryCallPromotesFlat} — {@code IntStream.iterate(50, n -> n + 7)}), and
    // (b) it has TWO OR MORE arguments ({@link #expressionLambdaFactoryCallFoldsAsMultiArgGroup} —
    // {@code Flux.usingWhen(connectionFactory.create(), connection -> …, Connection::close)}), which
    // {@link #promotedFactoryRootDoc} renders through its width-driven multi-argument {@link Doc#group} — {@code
    // Flux.usingWhen(} on the root line, arguments fanned one per line, {@code )} dedented — never through
    // {@link #groupedPromotedMethodCall}'s source-shape-sensitive lambda-hug branches. Both routes are a pure function
    // of the AST plus the render column, so the fold stays a fixpoint. A SINGLE expression-lambda-argument factory call
    // that does not promote flat ({@code Type.of(x -> body)}) is still held back: it would route through
    // {@code groupedPromotedMethodCall}'s {@code groupedPromotedExpressionLambda} / packed-body branches, which read the
    // author's source shape, so it stays on the split shape until the deferred lambda-arrow seam lands. A block-lambda
    // factory call is likewise held back.
    private boolean fanFoldsFactoryRoot(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        return methodChainPlanner.promotesFirstCall(candidate.root())
            && calls.size() >= 2
            && !methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
            && (!methodCallSegmentHasExpressionLambdaArgument(calls.getFirst())
                || expressionLambdaFactoryCallPromotesFlat(calls.getFirst())
                || expressionLambdaFactoryCallFoldsAsMultiArgGroup(calls.getFirst()));
    }

    private Doc fanFactoryRootFoldLayout(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        MethodCallExpr factoryCall = calls.getFirst();
        List<MethodCallExpr> selectors = new ArrayList<>(calls.subList(1, calls.size()));
        return Doc.concat(
            promotedFactoryRootDoc(factoryCall),
            chainContinuation(factoryCall, methodCallChainSegments(selectors, candidate.tail()))
        );
    }

    // Object-creation root cutover seam (End-state A), the constructor-root analogue of the factory-root promotion
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

    // Single selector: the lone segment fans onto its own dotted continuation line. This reproduces the exact
    // shape rankedSingleSegmentChain / rankedObjectRootSingleSegmentChain built inline before this extraction —
    // the segment renders through the ordinary (not on-own-line) segment group so a single-simple-arg tail stays
    // compact — so those callers stay byte-identical.
    private Doc fanSingleSelectorLayout(ChainFanCandidate candidate) {
        return Doc.concat(
            fanRootDoc(candidate.root()),
            chainContinuation(methodCallChainSegment(candidate.calls().getFirst(), candidate.tail()))
        );
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
    // Gated on {@code calls.size() >= 3}: that is exactly {@code chainBreaksByRule}'s plain-receiver threshold — a
    // trivial receiver is always a plain-receiver root — so the attach fires ONLY for a genuine CANONICAL fan (three or
    // more selectors) and never for a sub-threshold TWO-selector chain that reached this builder purely because its flat
    // form was over-width ({@code builder.defaultStatusHandler(a, b).filter(c)}). Attaching the first selector on such a
    // width-driven fan would put an over-wide {@code root.firstSelector(args)} opener on the first line, whose own
    // argument-list break then flips across passes — the oscillation Rule 1 must not introduce. A width-driven
    // two-selector fan keeps the fan-from-first shape below ({@code builder}⏎{@code .defaultStatusHandler(…)}⏎{@code
    // .filter(…)}), which is already a fixpoint.
    //
    // Additionally gated on the first selector being ATTACH-SAFE ({@link #firstSelectorAttachesSafely}): no arguments or
    // only simple leaf arguments ({@code .getRange()}, {@code .get(0)}, {@code .entrySet()}, {@code .validateOrder()}),
    // so it renders as one atomic {@code .selector(...)} token that NEVER opens its own broken argument list. A first
    // selector with a lambda, nested call, or multi-argument list ({@code target.computeIfAbsent(topicId, __ -> new X()
    // …)}) can break INTERNALLY, and that inner break's indentation is measured relative to the segment's live column —
    // which shifts once the previous pass glued the selector to the root — so the attached block reindents across passes
    // (the kafka {@code ConsumerGroupMember} oscillation). Such a chain keeps the fan-from-first shape below ({@code
    // target}⏎{@code .computeIfAbsent(…)}⏎…), where the selector's argument list breaks at a stable continuation column.
    //
    // Additionally gated on the root NOT being SHORTER than the indent unit ({@link #rootAvoidsShortRootPadding}). A
    // root shorter than one indent ({@code p}, {@code res}) drives {@code chainContinuation}'s short-root PADDING branch,
    // which dedent-aligns the fanned selectors under the root text rather than at the plain continuation indent. Attaching
    // the first selector there ({@code p.recordErrors()}⏎padded{@code .stream()}) diverges from the fan-from-first shape
    // the imperative fall-through renders once a re-format makes the selector arguments span source lines and the early
    // canonical route is skipped, so the padded-attach and the fan-from-first alternate across passes (the kafka
    // {@code Sender} / {@code DescribeConsumerGroupTest} indent oscillation). A short-rooted chain keeps the fan-from-first
    // shape, whose padding branch is already a fixpoint. Long roots (the maintainer's targets — {@code argument},
    // {@code response.unsentRequests}, {@code counterStream}) never touch the padding branch and attach cleanly.
    private boolean fanAttachesTrivialReceiverFirstSelector(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        return calls.size() >= 3
            && chainRootIsTrivialReceiver(candidate.root())
            && firstSelectorAttachesSafely(calls.getFirst())
            && rootAvoidsShortRootPadding(candidate.root());
    }

    private Doc fanTrivialReceiverAttachLayout(ChainFanCandidate candidate) {
        List<MethodCallExpr> calls = candidate.calls();
        List<MethodCallExpr> fannedSelectors = new ArrayList<>(calls.subList(1, calls.size()));
        return Doc.concat(
            fanRootDoc(candidate.root()),
            attachedFirstSelectorSegment(calls.getFirst()),
            chainContinuation(candidate.root(), methodCallChainSegments(fannedSelectors, candidate.tail()))
        );
    }

    // Multi-segment: one selector per line under the continuation indent, the same one-per-line layout the imperative
    // broken-chain tail produces (each segment measured at the continuation column, the final one carrying the tail).
    private Doc fanSelectorsLayout(ChainFanCandidate candidate) {
        return Doc.concat(
            fanRootDoc(candidate.root()),
            chainContinuation(candidate.root(), methodCallChainSegments(candidate.calls(), candidate.tail()))
        );
    }

    /**
     * Reports whether {@code root} is a TRIVIAL RECEIVER for the trivial-receiver first-selector attach in
     * {@link #chainFanOut}: a bare {@code NameExpr}, {@code FieldAccessExpr}, {@code this}, or {@code super}, and NOT a
     * type-like/factory qualifier ({@link MethodCallChainSourcePlanner#promotesFirstCall} — an uppercase name or type
     * {@code FieldAccessExpr}, whose first call is a factory invocation folded onto the root line by the factory-root seam
     * above). Method-call and object-creation roots are excluded by construction (they are not one of these kinds).
     *
     * <p>Keyed only on the root's AST kind — no width, no {@code wasMultiline}, no source-shape signal — because the attach
     * verdict is exactly the oscillation the End-state A cutover eliminated: a width/source-conditioned "first selector
     * attaches" flips between passes. A pure structural key is a fixpoint.
     */
    private boolean chainRootIsTrivialReceiver(Expression root) {
        if (methodChainPlanner.promotesFirstCall(root)) {
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
        return chainRootIsTrivialReceiver(methodCallChainAnalysis(expression).root());
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
        if (firstSelector.getTypeArguments().isPresent()) {
            return false;
        }
        if (methodCallSegmentHasComment(firstSelector) || !firstSelector.getAllContainedComments().isEmpty()) {
            return false;
        }
        if (!finalTrailingLineComments(firstSelector).isEmpty()) {
            return false;
        }
        return firstSelector.getArguments().stream().allMatch(this::isSimpleLeafArgument);
    }

    /**
     * Renders a trivial-receiver chain's attach-safe first selector ({@link #firstSelectorAttachesSafely}) as one flat,
     * SOURCE-NEUTRAL {@code .selector(arg, …)} token glued to the root's opening line in {@link #chainFanOut}. The selector
     * has only simple leaf arguments and no comment (both guaranteed by the attach-safe gate), so the compact join is its
     * complete rendering and it can never open its own broken argument list.
     *
     * <p>This deliberately does NOT go through {@link #methodCallChainSegment}: that shared renderer would take its
     * {@code sourceMultilineMethodCallSegmentArguments} branch and break the selector's argument list whenever the author
     * wrote the arguments across source lines — a source-shape signal. When attaching the selector pushes the opening line
     * over width (a long root prefix, e.g. a cast-wrapped initializer {@code (List<Foo>) fluentTemplate.to("…")}), that
     * source-driven break becomes VISIBLE and flips across passes: the flat re-format then keeps the selector inline while
     * the original multiline source broke it (the salesforce {@code CompositeApiCollectionsManualIT} oscillation). Emitting
     * one flat text makes the attached selector's shape a pure function of the AST, so both passes render it identically
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
     * Reports whether the trivial-receiver first-selector attach in {@link #chainFanOut} may use {@code root} without
     * triggering {@link #chainContinuation}'s short-root PADDING branch — i.e. the root's compact form is at least one
     * indent unit wide (or is not a stable single-line compact at all). The padding branch dedent-aligns the fan under a
     * sub-indent root text; attaching the first selector there diverges from the fan-from-first shape the imperative
     * fall-through renders on a source-multiline-argument re-format, so a short root ({@code p}, {@code res}) must keep the
     * fan-from-first layout to stay a fixpoint. Mirrors the exact predicate {@link #chainContinuation(Expression, List)}
     * uses to enter that branch, so the two stay in lockstep.
     */
    private boolean rootAvoidsShortRootPadding(Expression root) {
        return compactSingleLineRoot(root)
                .filter(rootText -> rootText.length() < options.indentUnit().length())
                .isEmpty();
    }

    /**
     * Renders the promoted factory call ({@code Type.factory(a, b)}) that heads a canonical-fan factory-root chain, matching
     * the root doc {@code MethodCallChainSourcePlanner.plan} produces when it promotes the first call of a static/factory
     * root. A zero/one-argument factory call renders through {@link #groupedPromotedMethodCall}
     * ({@code Doc.group(Type + softChainContinuation(.factory()))}, which keeps {@code Type.factory()} on one line when it
     * fits and splits to {@code Type}⏎{@code .factory()} only when the column forces it).
     *
     * <p>Canonical-fan cutover seam (End-state A), the multi-argument factory-root convergence. A multi-argument factory
     * call renders SOURCE-NEUTRALLY as a width-driven {@link Doc#group} of its argument list ({@code Type.factory(} then
     * each argument, the {@code )} glued to the last), so the {@code DocRenderer} keeps the arguments flat when they fit at
     * the promoted root's live column and breaks them one per line only on genuine overflow. This replaces
     * {@code expressionRenderer.format(factoryCall, root())}, which is NOT source-neutral for a promoted factory call whose
     * SCOPE broke onto its own source line ({@code StreamSupport}⏎{@code .stream(a, b)} → {@code wasMultiline(factoryCall)}
     * true even though the arguments are single-line): that path routes through the source-multiline-chain single-selector
     * layout and emits the whole {@code .factory(a, b)} as one FLAT non-breakable {@code Text}, so an over-width promoted
     * root stays flat on the multiline-scope pass and only breaks its arguments once a prior pass collapses the scope onto
     * the root line ({@code wasMultiline} false) and the breakable group returns — the factory-root arm of the
     * {@code source-multiline-method-root-chain-initializer} oscillation. Rendering the argument-list group directly makes
     * the arguments' break the renderer's width verdict at the true column on every pass, a fixpoint by construction.
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
        // Canonical-fan cutover seam (End-state A), the single-argument-chain factory-root convergence. A one-argument
        // promoted factory call whose sole argument is itself a fan-threshold chain ({@code Optional.of(a.b().c().d())},
        // {@code Arrays.stream(a.b().c())}) renders SOURCE-NEUTRALLY as a width-driven {@link Doc#group}: {@code Type.factory(}
        // glued on the root line, the argument fan under one continuation indent, the {@code )} dedented. This replaces
        // {@link #groupedPromotedMethodCall}, whose {@code softChainContinuation} group instead splits the SCOPE off the
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
        // {@link #groupedPromotedMethodCall}'s established shapes.
        if (
            factoryCall.getArguments().size() == 1
            && factoryCall.getArgument(0) instanceof MethodCallExpr chainArgument
            && factoryCall.getAllContainedComments().isEmpty()
        ) {
            Optional<Doc> fan = canonicalFanChain(chainArgument, "", LayoutContext.root());
            if (fan.isPresent()) {
                String prefix = calls.methodCallPrefix(factoryCall);
                Doc fanDoc = fan.orElseThrow();
                return Doc.group(
                    Doc.concat(
                        Doc.text(prefix + "("),
                        Doc.indent(Doc.concat(Doc.SOFT_LINE, fanDoc)),
                        Doc.SOFT_LINE,
                        Doc.text(")")
                    )
                );
            }
        }
        // Review round 2 (comment #1, "CacheFactory.newBuilder() should stick together, with higher priority than keeping
        // `= root` on the LHS line"). A ZERO-ARGUMENT promoted factory call ({@code CacheFactory.newBuilder()}) renders as
        // ATOMIC text ({@code Type.factory()}) rather than through {@link #groupedPromotedMethodCall}, whose
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
        // LHS (the {@code Flux.usingWhen(…)} case, comment #4) keeps the factory root ATTACHED because the atomic opener
        // fits there — the fit gate attaches when there is space and only breaks after {@code =} when there is not.
        if (factoryCall.getArguments().isEmpty() && factoryCall.getAllContainedComments().isEmpty()) {
            return Doc.text(calls.methodCallPrefix(factoryCall) + "()");
        }
        if (factoryCall.getArguments().size() <= 1) {
            return groupedPromotedMethodCall(factoryCall);
        }
        if (!factoryCall.getAllContainedComments().isEmpty()) {
            return expressionRenderer.format(factoryCall, LayoutContext.root());
        }
        String prefix = calls.methodCallPrefix(factoryCall);
        return Doc.group(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.SOFT_LINE,
                        calls.methodCallArgumentList(prefix, factoryCall.getArguments(), Doc.LINE)
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Canonical-fan cutover seam (End-state A), the expression-lambda factory-root convergence. Reports whether a promoted
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
            methodCallSegmentHasBlockLambdaArgument(factoryCall)
            || !factoryCall.getAllContainedComments().isEmpty()
        ) {
            return false;
        }
        boolean lambdasAreCompactExpressionBodies = factoryCall.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .allMatch(lambda -> lambda.getExpressionBody().isPresent()
                    && lambda.getAllContainedComments().isEmpty()
                    && !sourceShapePolicy.wasMultiline(lambda));
        return lambdasAreCompactExpressionBodies
            && rootLineWidth(factoryCall, compactSource.compact(factoryCall), LayoutContext.root()) <= options.lineWidth();
    }

    /**
     * Canonical-fan cutover seam (End-state A), review round 2 (comment #4). Reports whether a promoted factory call
     * carrying an expression lambda may fold onto the root line through {@link #chainFanOut}'s factory promotion because it
     * has TWO OR MORE arguments, so {@link #promotedFactoryRootDoc} renders it through the width-driven MULTI-ARGUMENT
     * {@link Doc#group} ({@code Flux.usingWhen(} on the root line, each argument fanned one per line, {@code )} dedented)
     * rather than through {@link #groupedPromotedMethodCall}'s source-shape-sensitive single-argument lambda-hug branches.
     * This keeps the factory root ({@code Type.factory(}) glued to the root line and only fans its argument list — the
     * maintainer's "class + method should not break until there is a space left" for a lambda-carrying factory call whose
     * whole flat form overflows (so {@link #expressionLambdaFactoryCallPromotesFlat} declines it).
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
            || methodCallSegmentHasBlockLambdaArgument(factoryCall)
            || !factoryCall.getAllContainedComments().isEmpty()
        ) {
            return false;
        }
        return factoryCall.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .allMatch(lambda -> lambda.getExpressionBody().isPresent()
                    && lambda.getAllContainedComments().isEmpty());
    }

    /**
     * Renders the object-creation root ({@code new Type(a, b, c, d)}) that heads a canonical-fan constructor-root chain
     * SOURCE-NEUTRALLY, the object-creation analogue of {@link #promotedFactoryRootDoc}. The constructor arguments render
     * through a width-driven {@link Doc#group} ({@code new Type(} then each argument, the {@code )} glued to the last), so
     * the {@code DocRenderer} keeps them flat when they fit at the root's live column and breaks them one per line only on
     * genuine overflow.
     *
     * <p>Canonical-fan cutover seam (End-state A), the constructor-root convergence — the object-creation analogue of the
     * factory-root {@code source-multiline-method-root-chain-initializer} oscillation. {@code chainFanOut} rebuilds the
     * root once per pass; for an object-creation root it previously delegated to {@code expressionRenderer.format(root,
     * root())} and the imperative fall-through delegated to {@code brokenObjectCreationRenderer}. Those two paths disagree
     * for a multi-segment constructor-rooted chain whose non-final SELECTOR arguments break across source lines between
     * passes ({@code new EndpointFactory(alpha, beta, gamma, delta).generate(…, Instance.builder()…build()).blockFirst(…)}):
     * on the flat-selector pass {@code chainHasSourceMultilineArguments} is false, the early canonical-fan route fires, and
     * {@code chainFanOut} renders the root through {@code expressionRenderer.format} → {@code ObjectCreationPrinter}'s
     * width-driven {@code Doc.group} (flat when the constructor line fits); on the re-format the {@code .generate(…)}
     * arguments now span lines, {@code chainHasSourceMultilineArguments} is true, the early route is skipped, and the
     * imperative fall-through renders the root through {@code brokenObjectCreationRenderer}, whose {@code forceBreak}
     * argument shape always puts each constructor argument on its own line — so a constructor line that fits flips
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
    private boolean objectCreationRootIsWidthDrivenFanEligible(Expression root) {
        return root instanceof ObjectCreationExpr objectCreation
            && objectCreation.getAnonymousClassBody().isEmpty()
            && !objectCreation.getArguments().isEmpty()
            && objectCreation.getAllContainedComments().isEmpty();
    }

    private boolean methodCallSegmentHasNoOwnContainedComments(MethodCallExpr expression) {
        List<Comment> containedComments = expression.getAllContainedComments();
        if (containedComments.isEmpty()) {
            return true;
        }
        return expression.getScope()
                .map(scope -> {
                    List<Comment> scopeComments = scope.getAllContainedComments();
                    return containedComments.stream().allMatch(scopeComments::contains);
                })
                .orElse(false);
    }

    private boolean canKeepSuffixAttachedToPromotedBlockLambdaRoot(
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (
            chainPlan.rootRendering() != MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
            || !(root instanceof MethodCallExpr methodRoot)
            || calls.size() != 1
            || !methodCallSegmentHasBlockLambdaArgument(methodRoot)
            || !methodRootCanKeepSingleSuffixAttached(methodRoot)
            || methodCallSegmentHasComment(calls.getFirst())
        ) {
            return false;
        }
        return true;
    }

    private Optional<MethodCallChainSourcePlanner.MethodCallChainPlan> promoteFirstBlockLambdaCallWithLambdaBodyComments(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan
    ) {
        List<MethodCallExpr> calls = analysis.calls();
        if (
            chainPlan.root() != analysis.root()
            || calls.size() < 2
            || !methodChainPlanner.promotesFirstCall(analysis.root())
        ) {
            return Optional.empty();
        }
        MethodCallExpr firstCall = calls.getFirst();
        if (
            !methodCallSegmentHasBlockLambdaArgument(firstCall)
            || methodCallSegmentHasLeadingLineComment(firstCall)
            || methodCallSegmentHasNameComment(firstCall)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            new MethodCallChainSourcePlanner.MethodCallChainPlan(
                firstCall,
                new ArrayList<>(calls.subList(1, calls.size())),
                MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
            )
        );
    }

    private boolean canAttachFirstSegmentToSimpleRoot(
            MethodCallExpr expression,
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            List<MethodCallExpr> calls,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis
    ) {
        if (
            chainPlan.rootRendering() != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            || calls.size() < 2
            || analysis.hasComments()
            || !analysis.sourceMultilineChain()
            || chainPlan.root() instanceof MethodCallExpr
            || chainPlan.root() instanceof ObjectCreationExpr
            || rootIsEnclosedFanningChain(chainPlan.root())
            || sourceFirstLineIsOnlyChainRoot(chainPlan.root(), expression)
            || !sourceShapePolicy.startsOnSameLine(chainPlan.root(), calls.getFirst().getName())
        ) {
            return false;
        }
        MethodCallExpr firstCall = calls.getFirst();
        return (
            !methodCallSegmentHasBlockLambdaArgument(firstCall)
            && (sourceShapePolicy.fitsOnOneLine(firstCall, lineWidth(LayoutWidth.LineBudget.CURRENT))
                || layoutWidth.line(LayoutWidth.LineBudget.CURRENT, this.calls.methodCallPrefix(firstCall) + "(") <= options.lineWidth())
        );
    }

    /**
     * Reports whether a chain root is a parenthesized (or parenthesized-cast) expression wrapping a fan-threshold method-call
     * chain — {@code ((OffsetFetchRequestData) res.unsentRequests.get(0).requestBuilder().build().data())}. Such a root
     * renders across multiple lines (its inner chain fans by the canonical rule), so its closing {@code )} lands on a
     * continuation line; whether the chain's FIRST selector ({@code .groups()}) attaches to that {@code )} line then depends
     * purely on the author's source shape ({@code canAttachFirstSegmentToSimpleRoot}'s {@code startsOnSameLine} probe),
     * flipping {@code .data()).groups()} ⇄ {@code .data())}⏎{@code .groups()} across passes — the
     * {@code CommitRequestManagerTest} residual. Withholding the attach here fans the first selector onto its own dotted line
     * on both passes (the collapsed-source fixpoint the fanned re-format already settles on), a source-neutral verdict.
     * Keyed strictly on an enclosed/cast root whose inner chain fans ({@code chainFansByCanonicalRule}); a parenthesized
     * non-chain receiver ({@code (a + b).foo()}) renders on one line and keeps its established attach.
     */
    private boolean rootIsEnclosedFanningChain(Expression root) {
        if (!(root instanceof EnclosedExpr enclosed)) {
            return false;
        }
        Expression inner = enclosed.getInner();
        if (inner instanceof CastExpr cast) {
            inner = cast.getExpression();
        }
        return inner instanceof MethodCallExpr innerChain && chainFansByCanonicalRule(innerChain);
    }

    private Doc firstSegmentAttachedToSimpleRootDoc(
            Expression root,
            MethodCallExpr firstCall,
            Optional<SourceMultilineLambdaCallLayout.AttachedFirstSegment> sourceMultilineLambdaPlan
    ) {
        if (sourceMultilineLambdaPlan.isPresent()) {
            SourceMultilineLambdaCallLayout.AttachedFirstSegment plan = sourceMultilineLambdaPlan.orElseThrow();
            Optional<Doc> sourceMultilineLambdaBody = comments.speculatively(
                () -> sourceMultilineLambdaCalls.attachedFirstSegment(root, firstCall)
            );
            if (sourceMultilineLambdaBody.isPresent()) {
                return sourceMultilineLambdaBody.orElseThrow();
            }
            Optional<Doc> huggableExpressionLambda = comments.speculatively(
                () -> huggableExpressionLambdaArguments.apply(plan.chainSegmentPrefix(), firstCall.getArguments())
            );
            if (huggableExpressionLambda.isPresent()) {
                return Doc.concat(expressionRenderer.format(root, LayoutContext.root()), huggableExpressionLambda.orElseThrow());
            }
            return Doc.concat(expressionRenderer.format(root, LayoutContext.root()), methodCallChainSegment(firstCall));
        }
        if (sourceShapePolicy.fitsOnOneLine(firstCall, lineWidth(LayoutWidth.LineBudget.CURRENT))) {
            return inlineMethodCall(firstCall);
        }
        return brokenFirstSegmentAttachedToSimpleRoot(root, firstCall);
    }

    private Optional<Doc> sourceMultilineFirstExpressionLambdaChain(
            MethodCallExpr expression,
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainTail finalSegmentSuffix,
            SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan
    ) {
        if (
            calls.size() != 2
            || root instanceof MethodCallExpr
            || root instanceof ObjectCreationExpr
            || !hasSingleExpressionLambdaArgumentAnyShape(calls.getFirst())
            || sourceMultilineLambdaPlan.firstCall().isEmpty()
            || sourceFirstLineIsOnlyChainRoot(root, expression)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                firstSegmentAttachedToSimpleRootDoc(root, calls.getFirst(), sourceMultilineLambdaPlan.firstCall()),
                methodCallChainSegment(calls.get(1), finalSegmentSuffix)
            )
        );
    }

    private String firstSegmentAttachedToSimpleRootFirstLine(Expression root, MethodCallExpr firstCall) {
        if (sourceShapePolicy.fitsOnOneLine(firstCall, lineWidth(LayoutWidth.LineBudget.CURRENT))) {
            return compactSource.compact(firstCall);
        }
        String typeArguments = firstCall.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return compactSource.compact(root) + "." + typeArguments + firstCall.getNameAsString() + "(";
    }

    private Doc brokenFirstSegmentAttachedToSimpleRoot(Expression root, MethodCallExpr expression) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        return Doc.concat(
            expressionRenderer.format(root, LayoutContext.root()),
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.indent(
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                        )
                    )
                )
            ),
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(")"))))
        );
    }

    private String methodCallSegmentPrefixText(MethodCallExpr expression) {
        return "."
            + expression.getTypeArguments()
                    .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    private boolean forcedSingleCallPrefixOverflows(
            MethodCallBreakMode breakMode,
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return breakMode.isForced()
            && expression.getScope().isPresent()
            && methodCallSegmentHasBlockLambdaArgument(expression)
            && layoutWidth.line(lineBudget, calls.methodCallPrefix(expression) + "(") > options.lineWidth();
    }

    private boolean chainHasSourceMultilineArguments(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan
    ) {
        if (
            analysis.root() instanceof MethodCallExpr methodRoot
            && !analysis.calls().isEmpty()
            && (sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)
                || sourceMultilineLambdaPlan.rootCanAttachExpressionLambdaBody())
        ) {
            return true;
        }
        for (int index = 0; index < Math.max(0, analysis.calls().size() - 1); index++) {
            MethodCallExpr call = analysis.calls().get(index);
            if (
                sourceShapePolicy.methodCallArgumentsSpanMultipleLines(call)
                || methodCallSegmentHasSourceMultilineBlockLambdaArgument(call)
                || sourceMultilineLambdaPlan.callCanAttachExpressionLambdaBody(index)
            ) {
                return true;
            }
        }
        return false;
    }

    private SourceMultilineLambdaChainPlan sourceMultilineLambdaChainPlan(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis
    ) {
        return sourceMultilineLambdaChainPlan(analysis.root(), analysis.calls());
    }

    private SourceMultilineLambdaChainPlan sourceMultilineLambdaChainPlan(
            Expression root,
            List<MethodCallExpr> calls
    ) {
        Optional<SourceMultilineLambdaCallLayout.AttachedFirstSegment> firstCall = calls.isEmpty()
            ? Optional.empty()
            : sourceMultilineLambdaCalls.attachedFirstSegmentPlan(calls.getFirst());
        boolean rootCanAttachExpressionLambdaBody = root instanceof MethodCallExpr methodRoot
            && sourceMultilineLambdaCalls.canAttachExpressionLambdaBody(methodRoot);
        List<Boolean> callCanAttachExpressionLambdaBody = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            callCanAttachExpressionLambdaBody.add(
                index == 0
                    ? firstCall.isPresent()
                    : sourceMultilineLambdaCalls.canAttachExpressionLambdaBody(calls.get(index))
            );
        }
        return new SourceMultilineLambdaChainPlan(
            rootCanAttachExpressionLambdaBody,
            callCanAttachExpressionLambdaBody,
            firstCall
        );
    }

    private Doc methodCallChainRootDoc(
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return switch (chainPlan.rootRendering()) {
            case INLINE_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? promotedMethodCallRoot(methodCall, firstLineWidth, layout)
                : expressionRenderer.format(chainPlan.root(), LayoutContext.root());
            case GROUPED_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? groupedPromotedMethodCall(methodCall)
                : expressionRenderer.format(chainPlan.root(), LayoutContext.root());
            case BROKEN_OBJECT_CREATION -> brokenObjectCreationRenderer.apply((ObjectCreationExpr) chainPlan.root());
            case EXPRESSION_RENDERER -> expressionRenderedChainRoot(chainPlan.root(), firstLineWidth);
        };
    }

    private Doc expressionRenderedChainRoot(
            Expression root,
            ToIntFunction<String> firstLineWidth
    ) {
        if (expressionRenderedChainRootBreaksMethodCall(root, firstLineWidth)) {
            return calls.brokenMethodCall((MethodCallExpr) root);
        }
        return expressionRenderer.format(root, LayoutContext.root());
    }

    /**
     * Whether {@link #expressionRenderedChainRoot} renders an {@link MethodCallChainSourcePlanner.ChainRootRendering#EXPRESSION_RENDERER}
     * root through {@link MethodCallPrinter#brokenMethodCall} — a multi-argument root that overflows its first line (or is
     * a source-multiline type-like root that does not fit) — rather than through ordinary expression dispatch. The negation
     * is the "plain expression-renderer root" case: {@code expressionRenderer.format(root, LayoutContext.root())}, which is
     * exactly the root {@link #chainFanOut} builds, so the multi-segment fall-through can route through the shared fan-out
     * builder byte-identically only when this returns {@code false}. Side-effect-free (no comment claim), so evaluating it
     * to steer the fall-through never double-claims a comment.
     */
    private boolean expressionRenderedChainRootBreaksMethodCall(
            Expression root,
            ToIntFunction<String> firstLineWidth
    ) {
        return root instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && (firstLineWidth.applyAsInt(compactSourceWidthText(methodCall)) > options.lineWidth()
                || (sourceMultilineTypeLikeRoot(methodCall)
                    && !sourceShapePolicy.fitsOnOneLine(methodCall, firstLineWidth)));
    }

    private boolean sourceMultilineTypeLikeRoot(MethodCallExpr methodCall) {
        return methodChainPlanner.methodCallHasTypeLikeScope(methodCall)
            && sourceShapePolicy.wasMultiline(methodCall);
    }

    private Doc singleSegmentMethodRootDoc(MethodCallExpr methodRoot) {
        Optional<Doc> sourceMultilineArguments =
            comments.speculatively(() -> calls.sourceMultilineArguments(methodRoot));
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> brokenScopedMethodRoot =
            comments.speculatively(() -> brokenTypeLikeScopedMethodRoot(methodRoot));
        if (brokenScopedMethodRoot.isPresent()) {
            return brokenScopedMethodRoot.orElseThrow();
        }
        if (
            layoutWidth.line(LayoutWidth.LineBudget.CURRENT, compactSourceWidthText(methodRoot)) > options.lineWidth()
            || methodCallRootScopeOverflows(methodRoot)
        ) {
            return methodCallChain(methodRoot, MethodCallBreakMode.FORCED, LayoutContext.root())
                    .orElseGet(() -> expressionRenderer.format(methodRoot, LayoutContext.root()));
        }
        return expressionRenderer.format(methodRoot, LayoutContext.root());
    }

    private Optional<Doc> brokenTypeLikeScopedMethodRoot(MethodCallExpr methodRoot) {
        Optional<MethodCallExpr> scopedCall = methodRoot.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(call -> call.getArguments().size() > 1)
                .filter(call -> call.getScope().filter(methodChainPlanner::promotesFirstCall).isPresent())
                .filter(call -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, compactSourceWidthText(call)) > options.lineWidth());
        if (scopedCall.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                calls.brokenMethodCall(scopedCall.orElseThrow()),
                chainContinuation(methodCallChainSegment(methodRoot))
            )
        );
    }

    private boolean methodCallRootScopeOverflows(MethodCallExpr methodRoot) {
        return methodRoot.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .map(scopedCall -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, 
                        compactSourceWidthText(scopedCall)
                    ) > options.lineWidth()
                )
                .orElse(false);
    }

    private String compactSourceWidthText(Expression expression) {
        return rawSource.normalizeWhitespace(rawSource.rawWithoutOwnComment(expression));
    }

    private Doc groupedPromotedMethodCall(MethodCallExpr expression) {
        Optional<Doc> sourceMultilineArguments =
            comments.speculatively(() -> calls.sourceMultilineArguments(expression));
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        if (
            expression.getArguments().size() > 1
            && !sourceShapePolicy.fitsOnOneLine(expression, lineWidth(LayoutWidth.LineBudget.CURRENT))
        ) {
            return calls.brokenMethodCall(expression);
        }
        Optional<Doc> huggableExpressionLambda =
            comments.speculatively(() -> groupedPromotedExpressionLambda(expression));
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(expression.getScope().orElseThrow()), expression)
                    .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                    .map(ignored -> expressionRenderer.format(expression, LayoutContext.root()))
                    .orElseGet(() -> Doc.concat(
                            expressionRenderer.format(expression.getScope().orElseThrow(), LayoutContext.root()),
                            chainContinuation(methodCallChainSegment(expression))
                    ));
        }
        return expression.getScope()
                .map(scope -> Doc.group(
                        Doc.concat(
                            expressionRenderer.format(scope, LayoutContext.root()),
                            softChainContinuation(methodCallChainSegment(expression))
                        )
                ))
                .orElseGet(() -> expressionRenderer.format(expression, LayoutContext.root()));
    }

    private Optional<Doc> groupedPromotedExpressionLambda(MethodCallExpr expression) {
        if (
            !sourceShapePolicy.expressionLambdaStartsOnSelectorLine(expression)
            || !expressionLambdaSpansMultipleLines(expression)
        ) {
            return Optional.empty();
        }
        return expression.getScope()
                .map(
                    scope -> compactSource.compact(scope)
                            + "."
                            + expression
                                    .getTypeArguments()
                                    .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                                    .orElse("")
                            + expression.getNameAsString()
                )
                .flatMap(prefix -> huggableExpressionLambdaArguments.apply(prefix, expression.getArguments()));
    }

    private Doc groupedPromotedRootWithSingleSegment(
            Expression root,
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(root), expression)
                    .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                    .map(ignored -> Doc.concat(rootDoc, methodCallChainSegment(expression, finalSegmentSuffix)))
                    .orElseGet(() -> Doc.concat(
                            rootDoc,
                            chainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
                    ));
        }
        if (
            sourceShapePolicy.expressionLambdaStartsOnSelectorLine(expression)
            && expressionLambdaSpansMultipleLines(expression)
            && expressionLambdaBodyOpenerOverflows(
                root,
                compactRootCallPrefix(root, expression),
                expression.getArguments(),
                layout
            )
        ) {
            return Doc.concat(
                rootDoc,
                chainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
            );
        }
        return Doc.group(
            Doc.concat(
                rootDoc,
                softChainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
            )
        );
    }

    private Optional<String> blockLambdaSegmentFirstLine(String root, MethodCallExpr expression) {
        String prefix = root
            + "."
            + expression
                    .getTypeArguments()
                    .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
        return huggableBlockLambdaFirstLine.apply(prefix, expression.getArguments());
    }

    /**
     * Refuses the compact-root-with-broken-final-segment shape for an object-creation-rooted return chain whose final
     * segment is a call with exactly one <em>simple</em> argument, so the chain fans the selector onto its own dotted
     * continuation line with that argument kept inline ({@code new X(...)}\n{@code .selector(arg)}) rather than opening the
     * single argument ({@code new X(...).selector(}\n{@code arg}\n{@code )}).
     *
     * <p>LDM-2f (#190), revising #236. #236 activated {@code leftEdgePrefix} for the return-chain gate so an over-width
     * {@code return new X(...).selector(arg)} stops hugging and breaks. The broken shape it then produced opened the
     * selector's single argument, which is gratuitous: on its own continuation line the whole {@code .selector(arg)}
     * routinely fits well within budget, and opening a one-simple-argument call adds two lines that carry no information.
     * By declining the arg-opening shape here, both broken-chain entry points converge on the fan-out: the direct
     * {@code compactRootWithBrokenFinalSegment} call in the forced single-segment branch and the compact alternative of
     * {@link #rankedObjectRootSingleSegmentChain} both see {@link Optional#empty()} and fall through to
     * {@link #objectRootSingleSegmentChain}, whose fan-out branch renders the single-simple-argument tail compact on its
     * dotted line (see the {@code singleSimpleMethodCallSegmentArgument} case there).
     *
     * <p><strong>Narrowly scoped.</strong> Gated on a non-empty {@link LayoutContext#leftEdgePrefix()} — only the return
     * chain threads one ({@code "return "}), so statement/if/assignment/field chains, which pass {@code root()}, are
     * untouched. Restricted to {@link ObjectCreationExpr} roots (the {@code new X(...).selector(arg)} slice #236 activated),
     * so method-rooted return chains keep their existing shape. "Simple" mirrors
     * {@link ControlConditionMethodCallLayout#hasComplexArgument}'s inverse via {@link #singleSimpleMethodCallSegmentArgument}
     * ({@code NameExpr | FieldAccessExpr | ThisExpr | SuperExpr | LiteralExpr}); a lambda, method-call, multi-argument, or
     * already-multiline tail is not simple and still opens exactly as before. A final-segment suffix (the statement
     * terminator carried through the chain) also excludes the tail, matching the ranker's own {@code !finalSegmentSuffix.isEmpty()}
     * gate, since the return keyword's {@code ;} is appended by the caller rather than threaded here.
     */
    private boolean refuseOpeningSingleSimpleReturnChainTail(
            Expression root,
            MethodCallExpr call,
            LayoutContext layout
    ) {
        return !layout.leftEdgePrefix().isEmpty()
            && root instanceof ObjectCreationExpr
            && singleSimpleMethodCallSegmentArgument(call);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            MethodCallChainTail.EMPTY,
            LayoutWidth.LineBudget.CURRENT,
            LayoutContext.root()
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(root, call, MethodCallChainTail.EMPTY, lineBudget, layout);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT,
            layout
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        if (refuseOpeningSingleSimpleReturnChainTail(root, call, layout)) {
            return Optional.empty();
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)
        ) {
            return Optional.empty();
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && methodCallSegmentHasSourceMultilineBlockLambdaArgument(methodRoot)
        ) {
            return Optional.empty();
        }
        if (
            root instanceof ObjectCreationExpr objectCreation
            && sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation)
        ) {
            return Optional.empty();
        }
        if (
            !(root instanceof MethodCallExpr)
            && !(root instanceof ObjectCreationExpr)
            && !(root instanceof FieldAccessExpr)
            && !sourceShapePolicy.startsOnSameLine(root, call.getName())
        ) {
            return Optional.empty();
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String callPrefix = compactSource.compact(root) + "." + typeArguments + call.getNameAsString();
        if (!compactRootFirstLineFits(root, callPrefix, call.getArguments(), lineBudget, layout)) {
            return Optional.empty();
        }
        Optional<Doc> huggableLambda =
            comments.speculatively(() -> huggableBlockLambdaArguments.apply(callPrefix, call.getArguments()));
        if (huggableLambda.isPresent()) {
            return Optional.of(Doc.concat(huggableLambda.orElseThrow(), finalSegmentSuffix.doc()));
        }
        if (sourceShapePolicy.expressionLambdaStartsOnSelectorLine(call) && expressionLambdaSpansMultipleLines(call)) {
            Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan = expressionLambdaArgumentPlan.plan(
                callPrefix,
                call.getArguments(),
                layout
            );
            if (expressionLambdaPlan.isEmpty()) {
                return Optional.empty();
            }
            Optional<Doc> huggableExpressionLambda = comments.speculatively(
                () -> huggableExpressionLambdaArguments.apply(callPrefix, call.getArguments())
            );
            if (huggableExpressionLambda.isPresent()) {
                if (
                    expressionLambdaPlan
                            .orElseThrow()
                            .bodyOpenerOverflows(
                                line -> compactRootLineWidth(root, line, lineBudget, layout),
                                options.lineWidth()
                            )
                ) {
                    return Optional.empty();
                }
                return Optional.of(Doc.concat(huggableExpressionLambda.orElseThrow(), finalSegmentSuffix.doc()));
            }
        }
        String prefix = callPrefix + "(";
        if (
            root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, prefix, lineBudget, layout) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (layoutWidth.line(lineBudget, prefix + ")") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        calls.methodCallArgumentList(callPrefix, call.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")" + finalSegmentSuffix)
            )
        );
    }

    private boolean compactRootFirstLineFits(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        Optional<String> blockLambdaFirstLine = huggableBlockLambdaFirstLine.apply(callPrefix, arguments);
        if (
            blockLambdaFirstLine
                    .filter(
                        firstLine -> compactRootLineWidth(
                            root,
                            firstLine,
                            lineBudget,
                            layout
                        ) > options.lineWidth()
                    )
                    .isPresent()
        ) {
            return false;
        }
        Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan = expressionLambdaArgumentPlan.plan(
            callPrefix,
            arguments,
            layout
        );
        return expressionLambdaPlan
                .map(plan -> plan.firstLineFits(
                        line -> compactRootLineWidth(root, line, lineBudget, layout),
                        options.lineWidth()
                ))
                .orElse(true);
    }

    /**
     * Measures a compact chain root's first line ({@code root.selector(args…}) at the column where the root renders.
     *
     * <p>C10 (#217): this gate reconstructs the root's start column from {@code range.begin.column}, a source-column
     * read that understates the rendered column once the root is reindented shallower than its true block/type depth. It
     * now also considers the root's rendered indentation ({@link LayoutWidth#nodeIndentWidth}, which counts every
     * enclosing type and block) and takes the <em>wider</em> of the two, so a root reindented flush-left inside deep
     * nesting is no longer measured as fitting at its stale shallow column and hugged over width. This mirrors the
     * sibling {@link ExpressionLambdaArgumentLayout} first-line gate (#226) and the depth-aware chain probes (#162).
     *
     * <p>Two measurement modes, keyed on whether a caller has threaded the same-line leading prefix through
     * {@link LayoutContext#leftEdgePrefix()}:
     *
     * <ul>
     *   <li><strong>Prefix threaded (LDM-2f, #190).</strong> When a caller supplies its fixed leading prefix — the
     *   {@code return } chain threads {@code "return "} — the rendered column is known exactly:
     *   {@code nodeIndentWidth(root) + leftEdgePrefix.length() + firstLine.length()}. The source-column floor is
     *   <em>dropped</em>, because it was only ever a stand-in for the prefix this arm now measures directly, and it could
     *   over- or under-count when the root was reindented away from its source column. A reindented-flat return chain whose
     *   compact first line is under budget by the stale source column but over budget once {@code return } is added
     *   (the {@code return } was worth exactly the missing width) is now correctly measured over width and fanned out.</li>
     *   <li><strong>No prefix threaded.</strong> Every other caller still passes {@code root()} (empty prefix), so the
     *   historical wider-of rule stands: {@code max(source-column, nodeIndentWidth) + firstLine.length()}. C10 (#217)
     *   added the {@code nodeIndentWidth} arm so a root reindented flush-left inside deep nesting is no longer measured as
     *   fitting at its stale shallow column and hugged over width; the source column is kept as the <em>floor</em> because
     *   it is where these callers' unmodelled leading prefix (a {@code NAME … = }, a continuation indent) still lives. This
     *   arm is byte-identical to before and stays until each caller's own {@code leftEdgePrefix} activation slice drops it
     *   too. A bare {@code nodeIndentWidth} swap without a threaded prefix did regress
     *   {@code source-multiline-method-root-chain-initializer}, which is why the floor stays for the unactivated callers.</li>
     * </ul>
     *
     * <p>This mirrors the sibling {@link ExpressionLambdaArgumentLayout} first-line gate (#226) and the depth-aware chain
     * probes (#162).
     */
    private int compactRootLineWidth(
            Expression root,
            String firstLine,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        // LDM-2f (#190): with the same-line prefix threaded, measure at the exact rendered column and drop the
        // source-column floor, which was only ever a stand-in for this prefix.
        if (!layout.leftEdgePrefix().isEmpty()) {
            return layoutWidth.nodeIndentWidth(root) + layout.leftEdgePrefix().length() + firstLine.length();
        }
        return root.getRange()
                .map(range -> Math.max(
                    Math.max(0, range.begin.column + 1) + firstLine.length(),
                    layoutWidth.nodeIndentWidth(root) + firstLine.length()))
                .orElseGet(() -> layoutWidth.line(lineBudget, firstLine));
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments,
            LayoutContext layout
    ) {
        return expressionLambdaArgumentPlan.plan(callPrefix, arguments, layout)
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(lineWidth(LayoutWidth.LineBudget.CONTINUATION), options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> compactRootLineWidth(root, line, LayoutWidth.LineBudget.CURRENT, layout),
                        options.lineWidth()
                ))
                .isPresent();
    }

    private String compactRootCallPrefix(Expression root, MethodCallExpr expression) {
        return compactSource.compact(root)
            + "."
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + types.compactJoinTypeLike(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    /**
     * Breaks chains that alternate method calls and field accesses as one structural chain.
     *
     * <p>A normal method-call chain can walk through method-call scopes directly. Mixed chains need a separate
     * structural-root path because field accesses can hide the earlier method-call root behind one or more field names.
     */
    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<Doc> segments = new ArrayList<>();
        Optional<Expression> root = collectMixedFieldMethodCallChain(expression, segments);
        if (root.isEmpty() || segments.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                expressionRenderer.format(root.orElseThrow(), LayoutContext.root()),
                chainContinuation(Doc.join(Doc.HARD_LINE, segments))
            )
        );
    }

    private Doc chainContinuation(Doc doc) {
        return Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, doc)));
    }

    private Doc chainContinuation(Expression root, List<Doc> segments) {
        Optional<String> compactRoot = compactSingleLineRoot(root);
        if (compactRoot.filter(rootText -> rootText.length() < options.indentUnit().length()).isPresent()) {
            String padding = " ".repeat(Math.max(0, compactRoot.orElseThrow().length() - 1));
            return Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.HARD_LINE,
                        segments.stream().map(segment -> linePadded(segment, padding)).toList()
                    )
                )
            );
        }
        return chainContinuation(Doc.join(Doc.HARD_LINE, segments));
    }

    private Doc linePadded(Doc doc, String padding) {
        if (padding.isEmpty()) {
            return doc;
        }
        return linePadded(doc, padding, true).doc();
    }

    private PaddedDoc linePadded(Doc doc, String padding, boolean lineStart) {
        return switch (doc) {
            case Doc.Text ignored -> new PaddedDoc(lineStart ? Doc.concat(Doc.text(padding), doc) : doc, false);
            case Doc.Concat concat -> {
                List<Doc> children = new ArrayList<>();
                boolean nextLineStart = lineStart;
                for (Doc child : concat.docs()) {
                    PaddedDoc padded = linePadded(child, padding, nextLineStart);
                    children.add(padded.doc());
                    nextLineStart = padded.lineStart();
                }
                yield new PaddedDoc(Doc.concat(children), nextLineStart);
            }
            // A fill threads continuation padding through its parts exactly like a concat, preserving the alternating
            // content/separator structure so its own greedy packing still applies after re-padding.
            case Doc.Fill fill -> {
                List<Doc> parts = new ArrayList<>();
                boolean nextLineStart = lineStart;
                for (Doc part : fill.parts()) {
                    PaddedDoc padded = linePadded(part, padding, nextLineStart);
                    parts.add(padded.doc());
                    nextLineStart = padded.lineStart();
                }
                yield new PaddedDoc(Doc.fill(parts), nextLineStart);
            }
            // A conditional group's alternatives are mutually exclusive layouts; only one renders, so each is padded from
            // the same incoming line-start rather than threaded in sequence. Like IfBreak, the choice is deferred to the
            // renderer, so the result conservatively reports lineStart=false for the token that follows the group.
            case Doc.ConditionalGroup conditionalGroup -> {
                List<Doc> alternatives = new ArrayList<>();
                for (Doc alternative : conditionalGroup.alternatives()) {
                    alternatives.add(linePadded(alternative, padding, lineStart).doc());
                }
                yield new PaddedDoc(Doc.conditionalGroup(alternatives), false);
            }
            // A best-fitting node's alternatives are mutually exclusive layouts too; only the rank-winner renders, so
            // each is padded from the same incoming line-start rather than threaded in sequence, and the token that
            // follows conservatively reports lineStart=false because which alternative rendered is a renderer decision.
            case Doc.BestFitting bestFitting -> {
                List<Doc> alternatives = new ArrayList<>();
                for (Doc alternative : bestFitting.alternatives()) {
                    alternatives.add(linePadded(alternative, padding, lineStart).doc());
                }
                yield new PaddedDoc(Doc.bestFitting(alternatives), false);
            }
            case Doc.Line ignored -> new PaddedDoc(
                Doc.concat(Doc.LINE, Doc.ifBreak(Doc.text(padding), Doc.EMPTY)),
                false
            );
            case Doc.SoftLine ignored -> new PaddedDoc(
                Doc.concat(Doc.SOFT_LINE, Doc.breakOnly(Doc.text(padding))),
                false
            );
            case Doc.HardLine ignored -> new PaddedDoc(Doc.concat(Doc.HARD_LINE, Doc.text(padding)), false);
            case Doc.Indent indented -> {
                PaddedDoc padded = linePadded(indented.doc(), padding, lineStart);
                yield new PaddedDoc(Doc.indent(padded.doc()), padded.lineStart());
            }
            case Doc.Group group -> {
                PaddedDoc padded = linePadded(group.doc(), padding, lineStart);
                // Preserve any group identity through re-padding so a dependent IfBreak still resolves this group.
                yield new PaddedDoc(Doc.group(padded.doc(), group.groupId()), padded.lineStart());
            }
            case Doc.IfBreak conditional -> new PaddedDoc(
                Doc.ifBreak(
                    linePadded(conditional.breakDoc(), padding, lineStart).doc(),
                    linePadded(conditional.flatDoc(), padding, lineStart).doc(),
                    conditional.groupId()
                ),
                false
            );
            case Doc.Label label -> {
                PaddedDoc padded = linePadded(label.doc(), padding, lineStart);
                yield new PaddedDoc(Doc.label(label.label(), padded.doc()), padded.lineStart());
            }
            // A line suffix renders nothing at its position and flushes at the line break, so it neither consumes the
            // line-start padding slot nor needs continuation padding inside its deferred content.
            case Doc.LineSuffix lineSuffix -> new PaddedDoc(lineSuffix, lineStart);
            // A break-parent marker renders nothing and only influences the enclosing group's fit, so it passes
            // through untouched and leaves the line-start padding slot for the next visible token.
            case Doc.BreakParent ignored -> new PaddedDoc(doc, lineStart);
        };
    }

    private Optional<String> compactSingleLineRoot(Expression root) {
        if (!root.getAllContainedComments().isEmpty() || sourceShapePolicy.wasMultiline(root)) {
            return Optional.empty();
        }
        return Optional.of(compactSource.compact(root));
    }

    private record PaddedDoc(Doc doc, boolean lineStart) {}

    private Doc softChainContinuation(Doc doc) {
        return Doc.indent(Doc.indent(Doc.concat(Doc.SOFT_LINE, doc)));
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (mixedFieldMethodCallSegmentCount(expression) < 2) {
            return Optional.empty();
        }
        return mixedFieldMethodCallStructuralRoot(expression);
    }

    private int mixedFieldMethodCallSegmentCount(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return 0;
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            int segments = mixedFieldMethodCallSegmentCount(methodScope);
            return segments == 0 ? 0 : segments + 1;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            return methodRoot.map(root -> mixedFieldMethodCallSegmentCount(root) + 1).orElse(0);
        }
        return 1;
    }

    private Optional<Expression> mixedFieldMethodCallStructuralRoot(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            return mixedFieldMethodCallStructuralRoot(methodScope);
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            return fieldAccessMethodRoot(fieldAccess).flatMap(this::mixedFieldMethodCallStructuralRoot);
        }
        return Optional.of(scoped);
    }

    private Optional<Expression> collectMixedFieldMethodCallChain(MethodCallExpr expression, List<Doc> segments) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodScope, segments);
            root.ifPresent(ignored -> segments.add(methodCallChainSegment(expression)));
            return root;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            if (methodRoot.isEmpty()) {
                return Optional.empty();
            }
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodRoot.orElseThrow(), segments);
            root.ifPresent(ignored -> segments.add(fieldAccessMethodCallSegment(fieldAccess, expression)));
            return root;
        }
        segments.add(methodCallChainSegment(expression));
        return Optional.of(scoped);
    }

    private Optional<MethodCallExpr> fieldAccessMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr methodCall) {
            return Optional.of(methodCall);
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessMethodRoot(innerFieldAccess);
        }
        return Optional.empty();
    }

    boolean methodCallChainHasComments(MethodCallExpr expression) {
        return methodCallChainAnalysis(expression).hasComments();
    }

    boolean methodCallChainHasFinalTrailingLineComment(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        MethodCallExpr finalCall = analysis.calls().isEmpty() ? expression : analysis.calls().getLast();
        return !finalTrailingLineComments(finalCall).isEmpty();
    }

    /**
     * Exposes the planner's source-line decision to statement routing so field-root fluent chains that were already
     * multiline are not compacted into an ordinary broken final call.
     */
    boolean methodCallChainIsSourceMultiline(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(analysis);
        return (
            analysis.sourceMultilineChain()
            || chainHasSourceMultilineArguments(analysis, sourceMultilineLambdaPlan)
            || (analysis.root() instanceof MethodCallExpr methodRoot
                && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot))
            || (analysis.root() instanceof ObjectCreationExpr objectCreation
                && sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation))
        );
    }

    MethodCallChainSourcePlanner.InitializerChainShape methodCallChainInitializerShape(MethodCallExpr expression) {
        return methodChainPlanner.initializerShape(methodCallChainAnalysis(expression));
    }

    private MethodCallChainSourcePlanner.MethodCallChainAnalysis methodCallChainAnalysis(MethodCallExpr expression) {
        return methodChainPlanner.analyze(
            expression,
            this::methodCallSegmentHasComment,
            this::methodCallSegmentHasNameComment,
            this::methodCallSegmentHasArgumentGapComment,
            this::methodCallSegmentHasBlockLambdaArgument,
            this::methodCallChainHasTrailingLineComments,
            this::rootHasTrailingLineCommentBeforeFirstSegment,
            this::chainHasInterSegmentLineComment
        );
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodChainPlanner.rootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodChainPlanner.rootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if (analysis.root() instanceof MethodCallExpr methodRoot && analysis.calls().size() == 1) {
            Optional<String> rootFirstLine = methodCallRootFirstLine(methodRoot);
            if (rootFirstLine.isPresent()) {
                return rootFirstLine.orElseThrow();
            }
        }
        if (analysis.root() instanceof MethodCallExpr && analysis.calls().size() == 1) {
            return compactSource.compact(expression);
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan plan = methodChainPlanner.plan(analysis, true);
        if (canAttachFirstSegmentToSimpleRoot(expression, plan, plan.calls(), analysis)) {
            return firstSegmentAttachedToSimpleRootFirstLine(plan.root(), plan.calls().getFirst());
        }
        if (plan.root() instanceof MethodCallExpr methodRoot) {
            Optional<String> rootFirstLine = methodCallRootFirstLine(methodRoot);
            if (rootFirstLine.isPresent()) {
                return rootFirstLine.orElseThrow();
            }
        }
        if (plan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION) {
            return objectCreationPrefix.apply((ObjectCreationExpr) plan.root()) + "(";
        }
        return compactSource.compact(plan.root());
    }

    private Optional<String> methodCallRootFirstLine(MethodCallExpr methodRoot) {
        String prefix = calls.methodCallPrefix(methodRoot);
        if (hasSingleExpressionLambdaArgument(methodRoot)) {
            return Optional.of(prefix + "(");
        }
        if (sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)) {
            return Optional.of(prefix + "(");
        }
        if (promotedRootArgumentsShouldBreak(methodRoot, lineWidth(LayoutWidth.LineBudget.CURRENT), LayoutContext.root())) {
            return Optional.of(prefix + "(");
        }
        if (!sourceShapePolicy.fitsOnOneLine(methodRoot, lineWidth(LayoutWidth.LineBudget.CURRENT))) {
            return Optional.of(prefix + "(");
        }
        if (methodCallSegmentHasBlockLambdaArgument(methodRoot)) {
            return huggableBlockLambdaFirstLine.apply(prefix, methodRoot.getArguments());
        }
        return Optional.empty();
    }

    /**
     * Breaks an expression-lambda-argument method-call root's argument list and glues the single final segment to its
     * close, the expression-lambda sibling of {@link #brokenRootWithAttachedFinalSegment} on the statement/field
     * single-segment chain path.
     *
     * <p>D1e (#190) threads {@code layout} so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this flat-gate for the eventual reflow-by-width flip. It is NOT yet consulted: the opener-fit decision
     * still uses the fixed-budget {@code layoutWidth.line(lineBudget, …)} floor exactly as before, so threading it is
     * byte-identical (the statement/field callers pass an empty-prefix context).
     */
    private Optional<Doc> expressionLambdaRootWithSingleSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            !hasSingleExpressionLambdaArgument(methodRoot)
            || !methodRoot.getAllContainedComments().isEmpty()
            || !methodCallSegmentHasNoOwnContainedComments(call)
            || methodCallSegmentHasComment(call)
            || methodCallSegmentHasBlockLambdaArgument(call)
        ) {
            return Optional.empty();
        }
        String prefix = calls.methodCallPrefix(methodRoot);
        if (layoutWidth.line(lineBudget, prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, expressionRenderer.format(methodRoot.getArgument(0), LayoutContext.root()))),
                Doc.HARD_LINE,
                Doc.text(")"),
                methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineBudget)
            )
        );
    }

    private boolean hasSingleExpressionLambdaArgument(MethodCallExpr expression) {
        return hasSingleExpressionLambdaArgumentAnyShape(expression)
            && !sourceShapePolicy.methodCallArgumentsSpanMultipleLines(expression);
    }

    private boolean hasSingleExpressionLambdaArgumentAnyShape(MethodCallExpr expression) {
        return expression.getArguments().size() == 1
            && expression.getArgument(0) instanceof LambdaExpr lambdaExpr
            && lambdaExpr.getExpressionBody().isPresent()
            && lambdaExpr.getAllContainedComments().isEmpty();
    }

    private boolean methodCallSegmentHasComment(MethodCallExpr expression) {
        return methodCallSegmentHasNameComment(expression)
            || methodCallSegmentHasLeadingLineComment(expression)
            || methodCallSegmentHasArgumentGapComment(expression);
    }

    private boolean methodCallSegmentHasLeadingLineComment(MethodCallExpr expression) {
        return !leadingLineCommentsBeforeSegment(expression).isEmpty();
    }

    private boolean methodCallSegmentHasArgumentGapComment(MethodCallExpr expression) {
        return commentedExpressionLists.hasUnprintedLineComments(expression, expression.getArguments());
    }

    private boolean methodCallSegmentHasLineComments(MethodCallExpr expression) {
        return commentedExpressionLists.hasLineComments(expression, expression.getArguments());
    }

    /**
     * Reports whether the only selector of a method-call-rooted chain carries a block comment parked in the gap between
     * the root and the selector, for example {@code create() /* doc *}{@code / .seal()}.
     *
     * <p>JavaParser attaches such a gap block comment to the selector's name (see {@code methodCallSegmentPrefix}), so the
     * stay-flat gate's contained-comment scan on the root misses it and the chain reaches the single-segment branch. This
     * predicate lets that branch break the segment onto its own continuation line, where the segment prefix re-emits the
     * comment with its source space, instead of gluing it flat and dropping the space. It deliberately accepts only a
     * block (or Javadoc) comment that starts after the root ends and before the selector name so an ordinary leading
     * comment already handled elsewhere, or a comment that belongs to the root, is not re-claimed here.
     */
    private boolean methodCallSegmentHasLeadingGapBlockComment(Expression root, MethodCallExpr segment) {
        return segment.getName()
                .getComment()
                .filter(comment -> comment instanceof BlockComment || comment instanceof JavadocComment)
                .filter(comment -> CommentIndex.startsBefore(comment, segment.getName()))
                .filter(comment -> root.getRange()
                            .flatMap(rootRange -> comment.getRange()
                                        .map(commentRange -> commentRange.begin.isAfter(rootRange.end))
                            )
                            .orElse(false)
                )
                .isPresent();
    }

    private boolean methodCallSegmentHasNameComment(MethodCallExpr expression) {
        return expression.getName()
                .getComment()
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()))
                .isPresent();
    }

    private boolean methodCallChainHasTrailingLineComments(List<MethodCallExpr> calls) {
        for (int index = 0; index + 1 < calls.size(); index++) {
            if (!trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return true;
            }
        }
        return !calls.isEmpty() && !finalTrailingLineComments(calls.getLast()).isEmpty();
    }

    /**
     * Structural comment-safety residue for the eventual retirement of the {@code selectorBrokeAfter} chain backbone
     * read ({@code CHAIN_SELECTOR_BROKE}; see {@code docs/proposals/hub-canonicalization-atomic-rewrite.md}, residue "A").
     * Reports whether a chain carries an inter-segment {@code //} <em>line</em> comment — the one comment class whose only
     * reason to keep the chain fanned is the author's line break, which the source-shape {@code selectorBrokeAfter} read
     * currently supplies. When that read is removed, a chain that fanned only to preserve such a comment would collapse
     * flat and the fan-only comment-preserving render would drop it; this predicate is the structural fan gate that must
     * take over so the fan survives the flip.
     *
     * <p>It covers the three inter-segment positions a {@code //} comment can occupy:
     * <ul>
     *   <li><b>root → first selector</b> — a line comment the author parked after the root and before the first selector,
     *       whether owned by the root as its trailing comment / root-to-first-selector-gap
     *       ({@link #rootHasTrailingLineCommentBeforeFirstSegment}) or attached as the first selector's leading comment
     *       ({@link #leadingLineCommentsBeforeSegment});</li>
     *   <li><b>dot-gap</b> — a line comment leading a later selector on its own continuation line, e.g. {@code .a()}⏎
     *       {@code // note}⏎{@code .b()} ({@link #leadingLineCommentsBeforeSegment} on each call);</li>
     *   <li><b>between selectors</b> — a trailing line comment in the gap after one selector and before the next, e.g.
     *       {@code .a() // note}⏎{@code .b()} ({@link #trailingLineCommentsBeforeNextSegment}).</li>
     * </ul>
     *
     * <p><strong>Line comments only, and why that keeps it a strict subset today.</strong> A {@code //} comment runs to
     * end-of-line, so it forces the next selector onto a later source line — which is exactly what makes
     * {@code selectorBrokeAfter} (hence {@code MethodCallChainAnalysis.sourceMultilineChain}) fire. So every chain this
     * predicate flags is one the live read already forces broken: this gate is <em>redundant</em> alongside the read and
     * the output is byte-identical. Block comments ({@code create() /* doc *}{@code / .seal()}) are deliberately excluded
     * because they can sit inline without a line break — the chain can stay flat and {@code sourceMultilineChain} can be
     * {@code false} — so folding them in would fan a chain the read does not, breaking the strict-subset property. This
     * predicate consults only the same line-comment candidate sets the imperative comment-preserving render consumes; it
     * claims no comment, so placement stays owned by the render.
     */
    private boolean chainHasInterSegmentLineComment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        // root -> first selector: root-owned trailing / gap line comment, or the first selector's own leading comment.
        if (rootHasTrailingLineCommentBeforeFirstSegment(root, calls)
            || methodCallSegmentHasLeadingLineComment(calls.getFirst())) {
            return true;
        }
        for (int index = 0; index < calls.size(); index++) {
            // dot-gap: a line comment leading a later selector on its own continuation line.
            if (index > 0 && methodCallSegmentHasLeadingLineComment(calls.get(index))) {
                return true;
            }
            // between selectors: a trailing line comment after this selector and before the next.
            if (index + 1 < calls.size()
                && !trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean methodCallSegmentHasBlockLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt()
                );
    }

    private boolean methodCallSegmentHasSourceMultilineBlockLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambdaExpr -> lambdaExpr.getBody().isBlockStmt())
                .anyMatch(lambdaExpr -> sourceShapePolicy.wasMultiline(lambdaExpr.getBody()));
    }

    private boolean canBreakAfterCompactExpressionLambdaRoot(
            MethodCallBreakMode breakMode,
            Expression root,
            List<MethodCallExpr> calls,
            SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan,
            LayoutContext layout
    ) {
        if (
            !breakMode.isForced()
            || calls.size() != 1
            || !(root instanceof MethodCallExpr methodRoot)
            || !methodCallSegmentHasExpressionLambdaArgument(methodRoot)
            || sourceMultilineLambdaPlan.rootCanAttachExpressionLambdaBody()
            || sourceMultilineLambdaPlan.anyNonFinalCallCanAttachExpressionLambdaBody()
            || !methodCallSegmentHasNoOwnContainedComments(calls.getFirst())
            || methodCallSegmentHasComment(calls.getFirst())
        ) {
            return false;
        }
        return rootLineWidth(root, compactSource.compact(root), layout) <= options.lineWidth();
    }

    private boolean methodCallSegmentHasExpressionLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getExpressionBody().isPresent()
                );
    }

    /**
     * Measures a chain root's compact form at the column where the root renders, feeding the compact-root break
     * decisions ({@link #canBreakAfterCompactExpressionLambdaRoot}, {@link #promotedRootArgumentsShouldBreak}).
     *
     * <p>C10 (#217): like {@link #compactRootLineWidth} it takes the wider of the source-column reconstruction and the
     * root's rendered indentation ({@link LayoutWidth#nodeIndentWidth}), so a root reindented shallower than its true
     * depth is no longer under-measured, while the source-column floor keeps the {@code = }/{@code return }/continuation
     * leading prefix accounted for (a bare {@code nodeIndentWidth} swap regressed the initializer/return fixtures). The
     * wider-of rule can only measure wider than before, so it is regression-free and byte-identical on the corpus.
     *
     * <p>LDM-2f / chain-unify U3 (#190): {@code layout} is threaded here so a follow-up can attribute the same-line
     * {@code layout.leftEdgePrefix()} at the rendered column. Unlike {@link #compactRootLineWidth}, this gate is NOT yet
     * activated to read the prefix: its sole consumer {@link #promotedRootArgumentsShouldBreak} is already reached by the
     * <em>initializer</em> chain carrying a real {@code "NAME = "} prefix (LDM-2f initializer slice), so dropping the
     * source-column floor here would change the initializer's promoted-root arg-break verdict — a corpus regression, not
     * a no-op. Activating it therefore waits until that promoted-root path is reviewed; for now the wider-of floor is
     * unchanged and every caller stays byte-identical.
     */
    private int rootLineWidth(Expression root, String text, LayoutContext layout) {
        return root.getRange()
                .map(range -> Math.max(
                    Math.max(0, range.begin.column - 1) + text.length(),
                    layoutWidth.nodeIndentWidth(root) + text.length()))
                .orElseGet(() -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, text));
    }

    private Doc inlineMethodCall(MethodCallExpr expression) {
        Doc scope = expression.getScope()
                .map(node -> expressionRenderer.format(node, LayoutContext.root()))
                .orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String arguments = "(" + compactSource.compactJoin(expression.getArguments()) + ")";
        return Doc.concat(scope, Doc.text("." + typeArguments + expression.getNameAsString() + arguments));
    }

    private Doc promotedMethodCallRoot(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        Optional<Doc> sourceMultilineArguments =
            comments.speculatively(() -> calls.sourceMultilineArguments(expression));
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        if (promotedRootArgumentsShouldBreak(expression, firstLineWidth, layout)) {
            return brokenPromotedMethodCallRoot(expression);
        }
        if (promotedNoArgRootScopeOverflows(expression, firstLineWidth)) {
            return expression.getScope()
                    .filter(FieldAccessExpr.class::isInstance)
                    .map(FieldAccessExpr.class::cast)
                    .map(scope -> promotedFieldAccessRootMethodCall(scope, expression))
                    .or(() -> expression.getScope().map(
                            scope -> Doc.concat(
                                expressionRenderer.format(scope, LayoutContext.root()),
                                chainContinuation(methodCallChainSegment(expression))
                            )
                    ))
                    .orElseGet(() -> inlineMethodCall(expression));
        }
        return inlineMethodCall(expression);
    }

    private Doc promotedFieldAccessRootMethodCall(FieldAccessExpr scope, MethodCallExpr expression) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.concat(
            Doc.text(compactSource.compact(scope.getScope())),
            chainContinuation(
                Doc.text("." + scope.getNameAsString() + "." + typeArguments + expression.getNameAsString() + "()")
            )
        );
    }

    private boolean promotedNoArgRootScopeOverflows(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return expression.getArguments().isEmpty()
            && expression.getScope().filter(FieldAccessExpr.class::isInstance).isPresent()
            && !sourceShapePolicy.fitsOnOneLine(expression, firstLineWidth);
    }

    private boolean promotedRootArgumentsShouldBreak(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        if (expression.getArguments().size() <= 1) {
            return false;
        }
        String compact = compactSource.compact(expression);
        return layoutWidth.line(LayoutWidth.LineBudget.CURRENT, compact) > options.lineWidth()
            || rootLineWidth(expression, compact, layout) > options.lineWidth()
            || (methodChainPlanner.methodCallStartsAfterScopeLine(expression)
                && selectorLineWidth(expression, compact, layout) > options.lineWidth())
            || ((sourceMultilineTypeLikeRoot(expression) || methodChainPlanner.methodCallStartsAfterScopeLine(expression))
                && firstLineWidth.applyAsInt(compact) > options.lineWidth());
    }

    private ToIntFunction<String> lineWidth(LayoutWidth.LineBudget lineBudget) {
        return text -> layoutWidth.line(lineBudget, text);
    }

    /**
     * Measures the selector's line width when the selector was broken onto its own continuation line after the scope.
     *
     * <p>C10 (#217): the sole caller ({@link #promotedRootArgumentsShouldBreak}) reaches it only when
     * {@code methodCallStartsAfterScopeLine} holds — the author already broke {@code .selector} onto a continuation line
     * under the scope — so the selector renders at that continuation indent, which is <em>deeper</em> than the
     * block/type nesting depth {@link LayoutWidth#nodeIndentWidth} counts. The name-token source column, not
     * {@code nodeIndentWidth}, is what actually describes where the preserved continuation sits, so the source column is
     * kept as the floor; taking the wider of it and {@code nodeIndentWidth} matches the root gates' shape and can only
     * ever measure wider (so it cannot regress), but the {@code nodeIndentWidth} arm rarely wins here because the
     * continuation indent already exceeds it.
     *
     * <p>LDM-2f / chain-unify U3 (#190): {@code layout} is threaded here so a follow-up can attribute the same-line
     * {@code layout.leftEdgePrefix()} at the rendered column. Like the sibling {@link #rootLineWidth} it is NOT yet
     * activated to read the prefix, and for the same reason: its sole caller {@link #promotedRootArgumentsShouldBreak} is
     * reached by the initializer chain carrying a real {@code "NAME = "} prefix, so dropping the floor here would move the
     * initializer's promoted-root arg-break verdict. The wider-of floor stays until that path is reviewed.
     */
    private int selectorLineWidth(MethodCallExpr expression, String text, LayoutContext layout) {
        return expression.getName()
                .getRange()
                .map(range -> Math.max(
                    Math.max(0, range.begin.column - 1) + text.length(),
                    layoutWidth.nodeIndentWidth(expression) + text.length()))
                .orElseGet(() -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, text));
    }

    private Doc brokenPromotedMethodCallRoot(MethodCallExpr expression) {
        String prefix = calls.methodCallPrefix(expression);
        // When the promoted root's argument list carries unclaimed gap comments (e.g. trailing notes on each argument of
        // Stream.concat(...) whose receiver sits on its own line under expand), route through the comment-aware
        // argument-list renderer so those comments survive. parenthesized() returns empty when there are no such
        // comments, so the comment-free path below stays byte-identical.
        Optional<Doc> commentedArguments = comments.speculatively(
            () -> commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments())
        );
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Doc methodCallChainSegment(MethodCallExpr expression) {
        return methodCallChainSegment(expression, false);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, MethodCallChainTail finalSegmentSuffix) {
        return methodCallChainSegment(expression, Optional.empty(), finalSegmentSuffix);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, boolean reserveStatementTerminator) {
        return methodCallChainSegment(expression, reserveStatementTerminator, lineWidth(LayoutWidth.LineBudget.CONTINUATION));
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChainSegmentAttachedToRootClose(
            expression,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT
        );
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodCallChainSegment(
            expression,
            Optional.empty(),
            finalSegmentSuffix,
            segment -> layoutWidth.line(lineBudget, ")" + segment)
        );
    }

    private ToIntFunction<String> objectRootSegmentWidth(
            ObjectCreationExpr root,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth
    ) {
        if (!objectRootUsesCompactLine(root, rootRendering)) {
            return segment -> layoutWidth.line(lineBudget, ")" + segment);
        }
        String rootText = compactSource.compact(root);
        return segment -> firstLineWidth.applyAsInt(rootText + segment);
    }

    private boolean objectRootUsesCompactLine(
            ObjectCreationExpr root,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering
    ) {
        return rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION
            && !sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(root);
    }

    private Optional<Doc> compactAttachedObjectRootSingleSegment(
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth,
            boolean sourceMultilineChain
    ) {
        if (
            !expression.getAllContainedComments().isEmpty()
            || (sourceMultilineChain && !finalSegmentSuffix.isEmpty())
            || methodCallSegmentHasBlockLambdaArgument(expression)
            || expression.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
        ) {
            return Optional.empty();
        }
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String segment = "."
            + typeArguments
            + expression.getNameAsString()
            + "("
            + compactSource.compactJoin(expression.getArguments())
            + ")"
            + finalSegmentSuffix;
        if (compactSegmentWidth.applyAsInt(segment) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(rootDoc, Doc.text(segment)));
    }

    private Doc objectRootSingleSegmentChain(
            ObjectCreationExpr objectCreation,
            Doc rootDoc,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            boolean sourceMultilineChain,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        if (sourceMultilineChain && methodCallSegmentHasLeadingLineComment(call)) {
            return Doc.concat(
                brokenObjectCreationRenderer.apply(objectCreation),
                chainContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
            );
        }
        if (sourceMultilineChain && !finalTrailingLineComments(call).isEmpty()) {
            return Doc.concat(
                rootDoc,
                chainContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
            );
        }
        ToIntFunction<String> compactSegmentWidth = objectRootSegmentWidth(
            objectCreation,
            rootRendering,
            lineBudget,
            firstLineWidth
        );
        if (
            compactSegmentWidth.applyAsInt(compactMethodCallChainSegment(call) + finalSegmentSuffix.text())
                > options.lineWidth()
        ) {
            // The compact chain (constructor plus the attached selector on one line) overflows, so the selector fans onto
            // its own continuation line. On the return chain (the only caller that threads a leftEdgePrefix, LDM-2f #190,
            // revising #236), when that selector is a call whose argument list is exactly one simple argument (a name,
            // field access, this/super, or literal — no lambda, no nested call, no multiple arguments), opening that single
            // argument (constructor \n .selector( \n arg \n )) is gratuitous: on its own continuation line the whole
            // {@code .selector(arg)} routinely fits well within budget. Render such a tail compact on its dotted line
            // through the ordinary segment renderer, whose group keeps {@code .selector(arg)} flat when it fits at the
            // continuation column and still breaks the argument only if it genuinely overruns. Multi-argument, lambda, and
            // already-broken selectors are excluded by singleSimpleMethodCallSegmentArgument, so they keep the existing
            // argument-opening fan-out. The leftEdgePrefix gate scopes this to the return chain: field/statement/initializer
            // callers pass root() (empty prefix), so their fan-out stays byte-identical.
            if (!layout.leftEdgePrefix().isEmpty() && singleSimpleMethodCallSegmentArgument(call)) {
                return Doc.concat(
                    rootDoc,
                    objectRootContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
                );
            }
            return Doc.concat(
                rootDoc,
                objectRootContinuation(brokenMethodCallChainSegment(call, finalSegmentSuffix))
            );
        }
        Optional<Doc> compactAttachedSegment = comments.speculatively(
            () -> compactAttachedObjectRootSingleSegment(
                rootDoc,
                call,
                finalSegmentSuffix,
                compactSegmentWidth,
                sourceMultilineChain
            )
        );
        if (compactAttachedSegment.isPresent()) {
            return compactAttachedSegment.orElseThrow();
        }
        Doc attachedSegment = methodCallChainSegment(
            call,
            Optional.empty(),
            finalSegmentSuffix,
            compactSegmentWidth
        );
        if (
            objectRootUsesCompactLine(objectCreation, rootRendering)
            && call.getArguments().isEmpty()
            && compactSegmentWidth.applyAsInt(
                compactMethodCallChainSegment(call) + finalSegmentSuffix.text()
            ) > options.lineWidth()
        ) {
            return Doc.concat(rootDoc, chainContinuation(attachedSegment));
        }
        return Doc.concat(rootDoc, attachedSegment);
    }

    private Doc objectRootContinuation(Doc doc) {
        return Doc.indent(Doc.concat(Doc.HARD_LINE, doc));
    }

    private Doc brokenMethodCallChainSegment(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return brokenMethodCallSegment(
            expression,
            "." + typeArguments + expression.getNameAsString(),
            Doc.EMPTY,
            finalSegmentSuffix
        );
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return methodCallChainSegment(
            expression,
            reserveStatementTerminator,
            compactSegmentWidth,
            MethodCallChainTail.EMPTY,
            false
        );
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth,
            MethodCallChainTail finalSegmentSuffix,
            boolean segmentOnOwnLine
    ) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        Doc segmentPrefix = methodCallSegmentPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments =
                comments.speculatively(() -> calls.emptyMethodCallArguments(prefix, expression));
            if (commentedArguments.isPresent()) {
                return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
            }
            return Doc.concat(segmentPrefix, Doc.text(prefix + "()" + finalSegmentSuffix));
        }
        Optional<Doc> sourceMultilineArguments = comments.speculatively(
            () -> sourceMultilineMethodCallSegmentArguments(prefix, expression, finalSegmentSuffix)
        );
        if (sourceMultilineArguments.isPresent()) {
            return Doc.concat(segmentPrefix, sourceMultilineArguments.orElseThrow());
        }
        Optional<Doc> huggableLambda =
            comments.speculatively(() -> huggableBlockLambdaArguments.apply(prefix, expression.getArguments()));
        if (huggableLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggableLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> commentedExpressionLambda =
            comments.speculatively(() -> commentedExpressionLambdaArgument.apply(prefix, expression));
        if (commentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, commentedExpressionLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> huggedCommentedExpressionLambda = comments.speculatively(
            () -> huggedCommentCarryingExpressionLambdaSegment(prefix, expression, finalSegmentSuffix)
        );
        if (huggedCommentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggedCommentedExpressionLambda.orElseThrow());
        }
        // Canonical-fan cutover seam (End-state A), the chain-SELECTOR expression-lambda position. A chain selector whose
        // sole trailing argument is an expression lambda ({@code .map(entry -> body)}) renders SOURCE-NEUTRALLY here,
        // replacing the {@code expressionLambdaStartsOnSelectorLine(expression) && expressionLambdaSpansMultipleLines(...)}
        // source-shape entry gate (plus the {@code huggableExpressionLambdaArguments} / packed-body / opener-overflow
        // sub-branches, all of which read {@code wasMultiline}/{@code bodyFirstSourceLineFits}). That gate re-rendered the
        // SAME selector two different ways across passes — the generic {@code Doc.group} argument shape on a flat-source
        // pass, the source-multiline hug on a re-format — so the segment's rendered width flipped and any enclosing
        // {@code bestFitting}/attach decision flipped with it. This is the withhold {@code chainFansByCanonicalRule}
        // ({@code methodCallSegmentHasExpressionLambdaArgument}) was load-bearing for; making the segment AST-pure is what
        // lets that withhold be removed so expr-lambda-selector chains fan. {@link #sourceNeutralExpressionLambdaSegment}
        // ranks two pure-AST arms (flat selector vs. hugged/fanned body) with {@link Doc#bestFitting}, so the DocRenderer
        // picks hug-vs-break at the true live column. Block-lambda and comment-carrying lambdas are handled by the earlier
        // branches (they never reach here), so this only ever sees a clean expression lambda.
        Optional<Doc> sourceNeutralExpressionLambda = comments.speculatively(
            () -> sourceNeutralExpressionLambdaSegment(prefix, expression, segmentPrefix, finalSegmentSuffix, segmentOnOwnLine)
        );
        if (sourceNeutralExpressionLambda.isPresent()) {
            return sourceNeutralExpressionLambda.orElseThrow();
        }
        Optional<Doc> commentedArguments = comments.speculatively(
            () -> commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments())
        );
        if (commentedArguments.isPresent()) {
            return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
        }
        String compactSegment = prefix
            + "("
            + methodCallSegmentArgumentsWidthText(expression.getArguments())
            + ")"
            + finalSegmentSuffix;
        if (methodCallSegmentArgumentsShouldBreak(
                expression,
                reserveStatementTerminator,
                compactSegment,
                compactSegmentWidth,
                segmentOnOwnLine
            )) {
            return brokenMethodCallSegment(expression, prefix, segmentPrefix, finalSegmentSuffix);
        }
        return Doc.concat(
            segmentPrefix,
            Doc.group(
                Doc.concat(
                    Doc.text(prefix + "("),
                    Doc.indent(
                        Doc.concat(
                            Doc.SOFT_LINE,
                            calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.LINE)
                        )
                    ),
                    Doc.SOFT_LINE,
                    Doc.text(")" + finalSegmentSuffix)
                )
            )
        );
    }

    private boolean methodCallSegmentArgumentsShouldBreak(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            String compactSegment,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        if (
            reserveStatementTerminator
            && !singleSimpleMethodCallSegmentArgument(expression)
            && finalSegmentRenderedWidth(expression, compactSegment, compactSegmentWidth, segmentOnOwnLine)
                > options.lineWidth()
        ) {
            return true;
        }
        return overwideTypeLikeScopeSegment(expression)
            && compactSegmentWidth.applyAsInt(compactSegment) > options.lineWidth();
    }

    /**
     * Measures where the final chain segment's compact form will actually land.
     *
     * <p>A segment that the chain places on its own continuation line is measured purely at that continuation
     * indent ({@code compactSegmentWidth}), because nothing precedes it on the line. The source-column estimate in
     * {@link #methodCallSegmentWidth} only describes a segment kept beside a preceding token on the same line, so
     * applying it to a one-per-line segment overstates the width by the segment's stale source indentation. That
     * over-measurement is what made an already-flat-fitting trailing call (such as {@code .collect(Collectors.toSet())})
     * break apart on the first pass and then collapse on the second, so a standalone segment must ignore the source
     * column to converge in one pass.
     */
    private int finalSegmentRenderedWidth(
            MethodCallExpr expression,
            String compactSegment,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        if (segmentOnOwnLine) {
            return compactSegmentWidth.applyAsInt(compactSegment);
        }
        return methodCallSegmentWidth(expression, compactSegment, compactSegmentWidth);
    }

    private boolean singleSimpleMethodCallSegmentArgument(MethodCallExpr expression) {
        if (expression.getArguments().size() != 1) {
            return false;
        }
        Expression argument = expression.getArgument(0);
        return argument.isNameExpr()
            || argument.isFieldAccessExpr()
            || argument.isThisExpr()
            || argument.isSuperExpr()
            || argument.isLiteralExpr();
    }

    private boolean overwideTypeLikeScopeSegment(MethodCallExpr expression) {
        return expression.getArguments().size() > 1
            && expression.getScope().filter(methodChainPlanner::promotesFirstCall).isPresent();
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
    private Optional<Doc> huggedCommentCarryingExpressionLambdaSegment(
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
            + methodCallSegmentPrefixText(methodCall).substring(1)
            + "(" + header.orElseThrow();
        if (layoutWidth.line(LayoutWidth.LineBudget.CURRENT, opener) > options.lineWidth()) {
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
                Doc.text(")")
            )
        );
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
    private Optional<Doc> flatHeadHuggedCommentLambdaChain(
            MethodCallExpr expression,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (
            analysis.calls().isEmpty()
            || analysis.sourceMultilineChain()
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
                .map(body -> appendFinalSegmentSuffix(body, finalSegmentSuffix));
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
        if (!scope.getAllContainedComments().isEmpty() || sourceShapePolicy.wasMultiline(scope)) {
            return Optional.empty();
        }
        if (scope instanceof MethodCallExpr methodCall) {
            if (!compactMethodCallChainSegmentCanStayFlat(methodCall) || methodCall.getScope().isEmpty()) {
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

    private Doc brokenMethodCallSegment(
            MethodCallExpr expression,
            String prefix,
            Doc segmentPrefix,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return Doc.concat(
            segmentPrefix,
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")" + finalSegmentSuffix)
        );
    }

    /**
     * Canonical-fan cutover seam (End-state A): renders a chain selector whose sole trailing argument is an expression
     * lambda ({@code .map(entry -> body)}) as a SOURCE-NEUTRAL {@link Doc#conditionalGroup} of two pure-AST arms, so the
     * {@code DocRenderer} picks flat-vs-hug at the true live column instead of the {@code wasMultiline}/
     * {@code bodyFirstSourceLineFits} predicates the old {@link #methodCallChainSegment} branch consulted. Returns empty
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
     *       single-segment {@link #compactRootWithBrokenFinalSegment} tail so the two paths converge on identical bytes.</li>
     * </ul>
     * Both arms are pure functions of the AST, so the selector's rendered width is a fixpoint and any enclosing
     * {@code bestFitting}/attach decision no longer flips across passes.
     */
    private Optional<Doc> sourceNeutralExpressionLambdaSegment(
            String prefix,
            MethodCallExpr expression,
            Doc segmentPrefix,
            MethodCallChainTail finalSegmentSuffix,
            boolean segmentOnOwnLine
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
        // spelling is source-shaped (present only when the body was multiline) and would flip the flat arm across passes. The
        // lambda body compacts cleanly through {@code compactSource.compact} (its {@code MethodCallExpr}/etc. cases reconstruct
        // canonical dot spacing), so the flat selector is reassembled here as {@code prefix(params -> compactBody)}.
        String flatLambda = lambdaParameters.apply(lambdaExpr) + " -> " + compactSource.compact(body);
        Doc flatBody = Doc.text(prefix + "(" + flatLambda + ")" + finalSegmentSuffix);
        // The broken/hug arm.
        //
        // A METHOD-CALL lambda body whose chain root is NOT an object creation ({@code entry -> meshCatalog.prepare(…)},
        // {@code outcome -> journalWriter.atInfo()….log(…)}) is the chain / fluent-builder / opener family this cutover
        // targets: it renders through the shared expression-lambda hug/fan renderer ({@code huggableExpressionLambdaArguments}
        // = ExpressionLambdaArgumentLayout#huggableMethodCallArguments) — the SAME machinery the old source-gated branch
        // called — reproducing every established shape (the U7 canonical fan, the over-width
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
        if (body instanceof MethodCallExpr bodyChain && !methodCallChainRootIsObjectCreation(bodyChain)) {
            Optional<Doc> hug = comments.speculatively(
                () -> huggableExpressionLambdaArguments.apply(prefix, expression.getArguments())
            );
            // The hug is only a valid conditionalGroup FALLBACK when it is a genuinely broken layout. The shared
            // huggableExpressionLambdaArguments renderer stays source-shape-gated for a short single-call body: its
            // {@code compactBodyWithClosingLine} branch fires only when the source lambda body started on the selector line
            // ({@code sourceMultilineBody}) and then hands back a FLAT one-liner measured at a fixed shallow budget (blind to
            // the selector's real continuation column), while on the collapsed-source re-format the same gate yields empty and
            // the selector breaks through the generic argument-list path. That flat-vs-broken flip across passes IS the
            // {@code ReplicaVerificationTool} oscillation: the hug arm is supposed to be the always-broken fallback the
            // conditionalGroup renders when {@code flatBody} overflows, but a flat hug renders the over-wide selector flat.
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
            // hug does not oscillate at a shallow column — it was already format-twice stable before this seam — so keeping
            // its original hug is safe. A hug that DOES carry a forced break (the fan / over-width hug / opener hug for a
            // fan-threshold or overflowing chain body) is a real broken layout and is kept unchanged, preserving those
            // established shapes. Yielding empty when the renderer withholds the body entirely is preserved.
            if (hug.isEmpty()) {
                return Optional.empty();
            }
            Doc hugDoc = hug.orElseThrow();
            hugBody = DocRenderer.containsHardLine(hugDoc) || !bodyIsSingleCallSafeForBrokenSegment(bodyChain)
                ? Doc.concat(hugDoc, finalSegmentSuffix.doc())
                : singleCallBodyOpenerHugOrBrokenSegment(prefix, expression, lambdaExpr, bodyChain, finalSegmentSuffix, segmentOnOwnLine);
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
                ? expressionBodyOpenerHug(prefix, expression, body, finalSegmentSuffix)
                : Optional.empty();
            hugBody = openerHug.orElseGet(
                () -> brokenMethodCallSegment(expression, prefix, Doc.EMPTY, finalSegmentSuffix)
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
     * {@code huggableExpressionLambdaArguments} renderer, and NOT the {@code binaryMethodCallBodyWithOpener} path (gated on
     * {@code sourceMultilineBinaryMethodCallBody}) whose source-shape read oscillated {@code .map(x -> x.f(a) == ALLOWED)} in
     * kafka {@code AuthHelper} and forced round 2 to drop the binary hug. The direct helper renders the operands with a pure
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
            MethodCallChainTail finalSegmentSuffix
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
            () -> expressionLambdaLogicalBinaryBodyOpenerHug.apply(prefix, expression)
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
            () -> huggableExpressionLambdaArguments.apply(prefix, expression.getArguments())
        );
        return hug.filter(DocRenderer::containsHardLine)
                .map(hugDoc -> Doc.concat(hugDoc, finalSegmentSuffix.doc()));
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
     * {@link #compactRootWithBrokenFinalSegment} tail — when it is not a fanned selector or the opener hug is unavailable
     * ({@code methodCallBodyWithOpener} withholds an empty-argument, source-multiline-scope, or comment-dropping body), so the
     * two paths still converge for every body this direct hug does not claim.
     */
    private Doc singleCallBodyOpenerHugOrBrokenSegment(
            String prefix,
            MethodCallExpr expression,
            LambdaExpr lambdaExpr,
            MethodCallExpr bodyCall,
            MethodCallChainTail finalSegmentSuffix,
            boolean segmentOnOwnLine
    ) {
        // Reached only when the shared renderer handed back the DEGENERATE FLAT one-liner (no forced break) for this
        // single-call body, so re-fetching it would find the same flat shape; build the opener hug directly instead.
        if (segmentOnOwnLine) {
            Optional<Doc> directOpener = comments.speculatively(
                () -> singleCallLambdaBodyOpenerHug(prefix, lambdaExpr, bodyCall, finalSegmentSuffix)
            );
            if (directOpener.isPresent()) {
                return directOpener.orElseThrow();
            }
        }
        return brokenMethodCallSegment(expression, prefix, Doc.EMPTY, finalSegmentSuffix);
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
            MethodCallChainTail finalSegmentSuffix
    ) {
        String parameters = lambdaParameters.apply(lambdaExpr);
        return expressionLambdaMethodCallBodyOpener.apply(parameters, bodyCall)
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
            && methodCallChainRootIsObjectCreation(bodyChain)
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

    private boolean sourceFirstLineIsOnlyChainRoot(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodChainPlanner.methodCallChainRoot(expression, calls);
        if (calls.size() < 2) {
            return false;
        }
        return sourceFirstLineIsOnlyChainRoot(root, expression);
    }

    private boolean sourceFirstLineIsOnlyChainRoot(Expression root, MethodCallExpr expression) {
        return rawSource.rawWithoutOwnComment(expression)
                .strip()
                .lines()
                .findFirst()
                .filter(line -> line.equals(compactSource.compact(root)))
                .isPresent();
    }

    private Optional<Doc> sourceMultilineMethodCallSegmentArguments(
            String prefix,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (
            !expression.getAllContainedComments().isEmpty()
            || !methodCallSegmentHasBlockLambdaArgument(expression)
            || !sourceShapePolicy.methodCallArgumentsSpanMultipleLines(expression)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")" + finalSegmentSuffix)
            )
        );
    }

    private boolean expressionLambdaSpansMultipleLines(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambda -> lambda.getExpressionBody().isPresent())
                .flatMap(lambda -> lambda.getRange().stream())
                .anyMatch(range -> range.begin.line < range.end.line);
    }

    private String methodCallSegmentArgumentsWidthText(NodeList<Expression> arguments) {
        return arguments.stream()
                .map(argument -> rawSource.normalizeWhitespace(rawSource.rawWithoutOwnComment(argument)))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * Estimates a chain segment's width when it is kept beside a preceding token on the same line.
     *
     * <p>C10 (#217): deliberately left source-relative. The reconstruction — the name token's source column minus its
     * offset within the segment — recovers where the whole segment starts <em>beside its preceding token</em> (see
     * {@link #finalSegmentRenderedWidth}), a source-shaped position that depends on what shares the line, not on the
     * segment's own block/type nesting depth. {@link LayoutWidth#nodeIndentWidth} measures only that nesting depth and
     * so cannot express the beside-a-token column, which is why the one-per-line caller already routes around this via
     * {@code segmentOnOwnLine}. The source column remains the faithful estimate for the beside-a-token case; a correct
     * rendered-column migration would need the same leading-offset machinery the root gates await (#190).
     */
    private int methodCallSegmentWidth(
            MethodCallExpr expression,
            String segment,
            ToIntFunction<String> fallbackWidth
    ) {
        return expression.getName()
                .getRange()
                .map(range -> {
                    int nameOffset = segment.indexOf(expression.getNameAsString());
                    if (nameOffset < 0) {
                        return fallbackWidth.applyAsInt(segment);
                    }
                    int leadingColumns = Math.max(0, range.begin.column - 1 - nameOffset);
                    return leadingColumns + segment.length();
                })
                .orElseGet(() -> fallbackWidth.applyAsInt(segment));
    }

    /**
     * Records the chain's flat-width decision when width is the actual cause of the break, so explain can report real
     * arithmetic instead of an opaque forced break.
     *
     * <p>Only a chain whose compact single-line form overflows the line budget is recorded as a width break: chains
     * forced apart purely by comments or by already-multiline source are not width decisions, so attributing them to
     * width would mislead. This is called after the printer has already committed to breaking, so it never changes the
     * layout that is produced.
     */
    private void recordChainWidthBreak(
            MethodCallExpr expression,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            LayoutWidth.LineBudget lineBudget
    ) {
        String compact = compactSource.compact(expression);
        int flatWidth = layoutWidth.line(lineBudget, compact);
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        int segments = analysis.calls().size() + 1;
        layoutDecisions.recordWidthBreak(
            "method chain",
            "java.expression:" + expression.getClass().getSimpleName(),
            chainPreview(compact),
            flatWidth,
            options.lineWidth(),
            segments
        );
    }

    /**
     * Builds a short headline snippet of the chain: the first two call selectors followed by an ellipsis when the chain
     * is longer, so the reader recognizes the construct without seeing the whole line.
     */
    private String chainPreview(String compact) {
        int firstCall = compact.indexOf('(');
        if (firstCall < 0) {
            return compact;
        }
        int firstClose = matchingClose(compact, firstCall);
        if (firstClose < 0) {
            return compact;
        }
        int secondDot = compact.indexOf('.', firstClose);
        if (secondDot < 0) {
            return compact.substring(0, firstClose + 1);
        }
        int secondCall = compact.indexOf('(', secondDot);
        if (secondCall < 0) {
            return compact.substring(0, firstClose + 1) + "…";
        }
        int secondClose = matchingClose(compact, secondCall);
        if (secondClose < 0) {
            return compact.substring(0, firstClose + 1) + "…";
        }
        String head = compact.substring(0, secondClose + 1);
        return secondClose + 1 < compact.length() ? head + "…" : head;
    }

    private int matchingClose(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private Doc methodCallSegmentPrefix(MethodCallExpr expression) {
        List<JavaCommentTrivia> leadingComments = leadingLineCommentsBeforeSegment(expression);
        Doc leading = Doc.concat(
            leadingComments
                    .stream()
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
        // A block or Javadoc comment interspersed between two chain links — e.g. `.define(A)` then `/** doc */` then
        // `.define(B)` — is parked on the B selector depending on layout. Recover it from the orphan pool first (the
        // expanded shape) so a single source-position query owns the slot for every whitespace shape, then fall through
        // to the selector's own comment (the canonical/collapsed shape). Both are claimed under the same anchor by
        // identity, so whichever shape applies, the comment renders exactly once.
        Doc interspersedOrphans = interspersedOrphanCommentsBeforeSelector(expression);
        // JavaParser attaches a line comment that sits between the scope and the selector to the selector name as its own
        // comment, so the same comment can also be offered by a neighboring slot: the leading-line slot above (same prefix
        // call) or the previous segment's between-segments trailing slot. The name comment is offered here under its own
        // (expression, OWN) ownership key — distinct from the bare (comment, INTERLEAVED) key those neighbors use — so the
        // dry-run records the true first-traversal claimant and {@code ownsHere} suppresses whichever offer lost. Output
        // is unchanged because the suppressed offer already lost the first-claim race and rendered empty. The
        // same-prefix leading offer is also excluded by identity here so the name slot never re-claims this segment's own
        // leading comment. A Javadoc selector comment is accepted alongside line and block comments because JavaParser
        // parses a `/** ... */` between chain links as a Javadoc attached to the next selector, and dropping it on
        // kind alone lost it in every shape.
        Optional<Comment> rawNameComment = expression.getName()
                .getComment()
                .filter(comment -> comment instanceof LineComment
                        || comment instanceof BlockComment
                        || comment instanceof JavadocComment)
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()))
                .filter(comment -> leadingComments.stream().noneMatch(leadingTrivia -> leadingTrivia.comment() == comment));
        Doc nameComment = rawNameComment
                .map(comment -> comments.comment(comment, expression, OwnerSlot.OWN))
                .orElse(Doc.EMPTY);
        if (nameComment == Doc.EMPTY) {
            return Doc.concat(leading, interspersedOrphans);
        }
        Doc namePrefix = rawNameComment
                .filter(comment -> comment instanceof BlockComment
                        && CommentIndex.startsOnSameLine(comment, expression.getName())
                )
                .map(ignored -> Doc.concat(nameComment, Doc.text(" ")))
                .orElseGet(() -> Doc.concat(nameComment, Doc.HARD_LINE));
        return Doc.concat(leading, interspersedOrphans, namePrefix);
    }

    /**
     * Recovers a block or Javadoc comment that sits between this segment's scope and its selector but that JavaParser
     * parked as an orphan of the call rather than as the selector's own trivia.
     *
     * <p>This is the orphan-bucket sibling of the selector's own-comment slot in {@link #methodCallSegmentPrefix}. At the
     * canonical and collapsed shapes JavaParser attaches a {@code .define(A) /** doc *}{@code / .define(B)} comment to the
     * {@code B} selector, so the own-comment slot renders it; an expanded whitespace shape re-buckets the identical
     * comment onto the enclosing call's orphan pool even though the AST is otherwise unchanged, so the own slot no longer
     * holds it and it is dropped. Selecting by source position from the orphan pool — strictly after the scope ends and
     * strictly before the selector begins — keeps the comment owned by this between-links slot whatever the layout.
     *
     * <p>The orphan pool is read directly from the node ({@link Node#getOrphanComments()}) rather than through the
     * comment-placement map, because the assignment/initializer renderers hand the chain printer a {@link Node#clone()
     * clone} of the chain expression (see {@code ExpressionRuleEnvelope.expressionWithoutOwnComment}). A clone carries its
     * orphan comments forward, but the identity-keyed placement map only knows the original parse node, so the map answers
     * empty for the clone; the node's own orphan list is the one association that survives the clone. Line comments are
     * deliberately excluded: they are already recovered by {@link #leadingLineCommentsBeforeSegment} and the
     * between-segments trailing slot. Each comment is offered under {@link OwnerSlot#ORPHAN} and claimed once, so the
     * canonical/collapsed shape — where the comment is the selector's own trivia and not in the orphan pool — is left
     * byte-identical.
     */
    private Doc interspersedOrphanCommentsBeforeSelector(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty() || expression.getOrphanComments().isEmpty()) {
            return Doc.EMPTY;
        }
        // Empty-argument selectors ({@code .util()}, {@code .build()}) are recovered here too. They route their inside-
        // the-parens orphans through {@code MethodCallPrinter.emptyMethodCallArguments}, but that owner now excludes the
        // between-links orphan (the one this slot selects: strictly after the scope ends and before the selector begins),
        // so the two slots partition the call's orphan pool by source position and each orphan is claimed exactly once.
        // Without this recovery the between-links comment before an empty-argument selector is dropped whenever the call
        // reaches the printer as a clone (the assignment/initializer value path), because the clone's orphans survive on
        // the node but not in the placement map the empty-argument owner reads.
        Expression scoped = scope.orElseThrow();
        return Doc.concat(
            expression.getOrphanComments()
                    .stream()
                    .map(JavaCommentTrivia::from)
                    .filter(trivia -> trivia.isBlock() || trivia.isJavadoc())
                    .filter(trivia -> trivia.liesBetween(scoped, expression.getName()))
                    .sorted((left, right) ->
                        CommentIndex.sourceOrderComparator().compare(left.comment(), right.comment()))
                    .map(trivia -> comments.comment(trivia, expression, OwnerSlot.ORPHAN))
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
    }

    private List<JavaCommentTrivia> leadingLineCommentsBeforeSegment(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return List.of();
        }
        int scopeEndLine = CommentIndex.endLine(scope.orElseThrow(), Integer.MIN_VALUE);
        int nameBeginLine = CommentIndex.beginLine(expression.getName(), Integer.MAX_VALUE);
        return commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.beginLine(Integer.MIN_VALUE) > scopeEndLine)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) < nameBeginLine)
                .sorted((left, right) -> CommentIndex.sourceOrderComparator().compare(left.comment(), right.comment()))
                .toList();
    }

    private List<Doc> methodCallChainSegments(List<MethodCallExpr> calls, MethodCallChainTail finalSegmentSuffix) {
        List<Doc> segments = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Optional<MethodCallExpr> next = i + 1 < calls.size() ? Optional.of(calls.get(i + 1)) : Optional.empty();
            // Every segment in this one-per-line layout renders alone on its own continuation line, so the final
            // segment must be measured at the continuation indent rather than its stale source column.
            segments.add(
                methodCallChainSegment(
                    calls.get(i),
                    next,
                    next.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY,
                    lineWidth(LayoutWidth.LineBudget.CONTINUATION),
                    true
                )
            );
        }
        return segments;
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, Optional<MethodCallExpr> nextCall) {
        return methodCallChainSegment(expression, nextCall, MethodCallChainTail.EMPTY);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, lineWidth(LayoutWidth.LineBudget.CONTINUATION));
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, compactSegmentWidth, false);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        MethodCallChainTail segmentSuffix = nextCall.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY;
        Doc segment = methodCallChainSegment(
            expression,
            nextCall.isEmpty(),
            compactSegmentWidth,
            segmentSuffix,
            segmentOnOwnLine
        );
        Doc trailingComment = nextCall
                .map(next -> trailingLineCommentBeforeNextSegment(expression, Optional.of(next)))
                .orElseGet(() -> finalTrailingLineComment(expression));
        if (trailingComment == Doc.EMPTY) {
            return segment;
        }
        return Doc.concat(segment, Doc.lineSuffix(Doc.concat(Doc.text(" "), trailingComment)));
    }

    private Doc appendFinalSegmentSuffix(Doc doc, MethodCallChainTail finalSegmentSuffix) {
        return finalSegmentSuffix.appendTo(doc);
    }

    private Doc trailingLineCommentBeforeNextSegment(Node expression, Optional<MethodCallExpr> nextCall) {
        if (nextCall.isEmpty()) {
            return Doc.EMPTY;
        }
        MethodCallExpr next = nextCall.orElseThrow();
        // A comment that sits on the same physical line as this segment's close can also be the same-line final-trailing
        // comment of an inner chain nested in this segment's lambda argument (the collapsed {@code .orElseThrow(...)) //
        // note .orElseGet(...)} shape, where the inner chain's last call and this outer link share a line). That inner
        // render runs first and already claimed it, so skip already-printed comments here to keep a single claim; output is
        // unchanged because a re-offer of a printed comment only ever rendered empty.
        List<Doc> sourceComments = trailingLineCommentsBeforeNextSegment(expression, next)
                .stream()
                .filter(trivia -> !comments.isPrinted(trivia))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private Doc rootTrailingLineCommentBeforeFirstSegment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return Doc.EMPTY;
        }
        return trailingLineCommentBeforeNextSegment(root, Optional.of(calls.getFirst()));
    }

    /**
     * Reports whether the chain root carries a trailing / root-to-first-selector-gap line comment that the imperative
     * chain renderer would re-emit through {@link #rootTrailingLineCommentBeforeFirstSegment}, for example
     * {@code new Zone(api, auth, "name") // restart note}⏎{@code .withProperty(...)}.
     *
     * <p><strong>Why the fan's other comment gates miss it.</strong> JavaParser attaches such a comment as the root
     * expression's <em>own</em> comment (the {@code ObjectCreationExpr} / root {@code MethodCallExpr} it trails), not as a
     * child or contained comment. {@link MethodCallChainAnalysis#rootHasComments()} is built from
     * {@link SourceShapePolicy#hasContainedComments(Node)} — which lists a node's orphans and its children's comments but
     * <em>not</em> the node's own comment — plus {@code rootToFirstSelectorGapHasBlockComment}, which matches only block
     * {@code /* *}{@code /} markers. The per-selector comment scans key on the selectors' own trivia, and the
     * trailing-line-comment scan only inspects the gaps <em>between</em> and <em>after</em> selectors. So a line comment
     * owned by the root in the gap before the first selector is invisible to every existing comment gate, the chain reads
     * comment-free, and the source-neutral fan ({@code chainFanOut}) re-renders the root through ordinary expression
     * dispatch — which does not carry the root's own comment — silently dropping it.
     *
     * <p>Detecting it here off the same {@link #trailingLineCommentsBeforeNextSegment} candidate set the renderer consumes
     * keeps the withhold verdict and the render in lockstep: any comment this predicate sees is one the imperative path
     * will actually place, so folding it into {@code hasComments} routes the chain off the fan and onto that
     * comment-preserving path without over- or under-withholding. This reads the candidate set only; it does not claim or
     * mark any comment printed, so the real render still owns placement.
     */
    private boolean rootHasTrailingLineCommentBeforeFirstSegment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        return !trailingLineCommentsBeforeNextSegment(root, calls.getFirst()).isEmpty();
    }

    private List<JavaCommentTrivia> trailingLineCommentsBeforeNextSegment(Node previous, MethodCallExpr next) {
        List<JavaCommentTrivia> candidates = new ArrayList<>();
        commentPlacement.trailingLineComment(previous).ifPresent(candidates::add);
        candidates.addAll(commentPlacement.containedComments(previous));
        candidates.addAll(lineCommentCandidatesBeforeNextSegment(next));
        // The three candidate sources overlap, so the same comment node can be offered more than once. Dedupe on
        // JavaParser comment identity rather than the record's value equality: structurally equal but distinct comment
        // nodes (e.g. two chain links carrying the same `// text`, or several empty `//` continuation markers) must each
        // survive, while a genuine reference-equal repeat from the overlapping sources is still collapsed.
        Set<Comment> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return candidates
                .stream()
                .filter(comment -> seen.add(comment.comment()))
                .filter(comment -> comment.startsAfterNodeOnSameLine(previous))
                .filter(comment -> comment.startsBeforeBeginLine(next.getName()))
                .toList();
    }

    private List<JavaCommentTrivia> lineCommentCandidatesBeforeNextSegment(MethodCallExpr next) {
        if (!next.getArguments().isEmpty()) {
            return commentPlacement.lineCommentsBeforeFirst(next, next.getArguments().get(0));
        }
        return commentPlacement.containedComments(next)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .toList();
    }

    /**
     * Keeps a final segment's same-line comment after the rendered call, even when the call arguments break.
     */
    private Doc finalTrailingLineComment(MethodCallExpr expression) {
        // The same final trailing line comment can be reached from neighboring chain renders (e.g. an outer segment's
        // argument render and the chain's final-segment slot). Skip comments already printed by an earlier traversal path
        // so this slot does not duplicate-claim them; output is unchanged because the first claimant placed the comment
        // and a re-offer only ever rendered empty.
        List<Doc> sourceComments = finalTrailingLineComments(expression)
                .stream()
                .filter(trivia -> !comments.isPrinted(trivia))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private List<JavaCommentTrivia> finalTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(expression))
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        return sourceComments;
    }

    private Doc fieldAccessMethodCallSegment(FieldAccessExpr fieldAccess, MethodCallExpr methodCall) {
        String typeArguments = methodCall.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.text(
            fieldAccessSuffixAfterMethodRoot(fieldAccess)
                + "."
                + typeArguments
                + methodCall.getNameAsString()
                + "("
                + compactSource.compactJoin(methodCall.getArguments())
                + ")"
        );
    }

    private String fieldAccessSuffixAfterMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr) {
            return "." + fieldAccess.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessSuffixAfterMethodRoot(innerFieldAccess) + "." + fieldAccess.getNameAsString();
        }
        return "." + fieldAccess.getNameAsString();
    }

    private record MethodCallChainTail(String text) {
        private static final MethodCallChainTail EMPTY = new MethodCallChainTail("");

        static MethodCallChainTail of(String text) {
            return text.isEmpty() ? EMPTY : new MethodCallChainTail(text);
        }

        boolean isEmpty() {
            return text.isEmpty();
        }

        Doc doc() {
            return Doc.text(text);
        }

        Doc appendTo(Doc doc) {
            return isEmpty() ? doc : Doc.concat(doc, doc());
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
