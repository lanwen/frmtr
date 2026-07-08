package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Plans and renders expression-body lambda arguments for call-like expressions.
 *
 * <p>This helper owns the call-argument side of expression lambdas: which lambda argument can be hugged, how the first
 * lambda line is measured, when the lambda body opener can stay with {@code ->}, and which method-call or constructor
 * body shapes can be packed before the closing call parenthesis. The boundary exists so {@link LambdaExpressionPrinter}
 * can keep ordinary lambda syntax and parameter comments while method-call and chain printers share one typed plan
 * instead of reconstructing lambda text independently.
 *
 * <p>Call, chain, and return contexts still own their surrounding root-width probes. This helper deliberately exposes a
 * typed {@link Plan} for those probes rather than returning partial rendering strings as an implicit contract.
 */
final class ExpressionLambdaArgumentLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> huggedLambdaBodyChainRenderer;

    private final Predicate<MethodCallExpr> lambdaBodyChainFansByCanonicalRule;

    private final JavaFormatRule<Statement> statementRenderer;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak;

    private final ToIntFunction<String> blockStatementWidth;

    private final LayoutWidth layoutWidth;

    private final ExpressionLambdaMethodCallBodyLayout methodCallBodies;

    private final TextBlockArgumentSourceLayout textBlockArguments;

    private final ExpressionLambdaClosingLayout closingLayout;

    ExpressionLambdaArgumentLayout(
            SourceShapePolicy sourceShapePolicy,
            RawSource rawSource,
            SourceText sourceText,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer,
            BiFunction<String, MethodCallExpr, Optional<Doc>> huggedLambdaBodyChainRenderer,
            Predicate<MethodCallExpr> lambdaBodyChainFansByCanonicalRule,
            JavaFormatRule<Statement> statementRenderer,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer,
            Function<LambdaExpr, String> lambdaParameters,
            BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
            ToIntFunction<String> blockStatementWidth,
            LayoutWidth layoutWidth
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.rawSource = rawSource;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.packedMethodCallChainBodyRenderer = packedMethodCallChainBodyRenderer;
        this.huggedLambdaBodyChainRenderer = huggedLambdaBodyChainRenderer;
        this.lambdaBodyChainFansByCanonicalRule = lambdaBodyChainFansByCanonicalRule;
        this.statementRenderer = statementRenderer;
        this.methodCallArgumentList = methodCallArgumentList;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.binaryExpressionNestedLinesRenderer = binaryExpressionNestedLinesRenderer;
        this.lambdaParameters = lambdaParameters;
        this.lambdaParametersShouldBreak = lambdaParametersShouldBreak;
        this.blockStatementWidth = blockStatementWidth;
        this.layoutWidth = layoutWidth;
        this.textBlockArguments = new TextBlockArgumentSourceLayout(sourceText, options, rawSource::raw);
        this.closingLayout = new ExpressionLambdaClosingLayout();
        this.methodCallBodies = new ExpressionLambdaMethodCallBodyLayout(
            options,
            expressionRenderer,
            compactJoin,
            this::methodCallPrefix,
            this::methodCallSelector,
            methodCallArgumentList,
            packedMethodCallChainBodyRenderer,
            this::expressionFirstLineWidth
        );
    }

    /**
     * Renders a lambda expression body as {@code parameters -> methodCall(} when that opener fits by itself.
     */
    Optional<Doc> methodCallBodyWithOpener(String parameters, MethodCallExpr methodCall) {
        return methodCallBodyWithOpener(parameters, methodCall, this::expressionFirstLineWidth);
    }

    /**
     * Column-carrying overload of {@link #methodCallBodyWithOpener(String, MethodCallExpr)}.
     *
     * <p>D3 keystone: {@code columnWidth} carries the true segment column the chain-segment call-site threads. It is
     * threaded-but-NOT-consulted (byte-identical): the opener-fit comparison below still uses the fixed-budget
     * {@link #expressionFirstLineWidth} / {@link #brokenArgumentListLambdaBodyWidth} probes exactly as before, and the
     * 2-arg form defaults {@code columnWidth} to {@link #expressionFirstLineWidth}. Consuming it — measuring the opener at
     * the true column — is the atomic D3 flip, out of scope for this slice.
     */
    Optional<Doc> methodCallBodyWithOpener(
            String parameters,
            MethodCallExpr methodCall,
            ToIntFunction<String> columnWidth
    ) {
        if (
            methodCall.getArguments().isEmpty()
            || methodCall.getScope().filter(sourceShapePolicy::wasMultiline).isPresent()
            || openerWouldDropPrefixComment(methodCall)
        ) {
            return Optional.empty();
        }
        String opener = methodCallPrefix(methodCall) + "(";
        String firstLine = parameters + " -> " + opener;
        if (
            expressionFirstLineWidth(firstLine) > options.lineWidth()
            || brokenArgumentListLambdaBodyWidth(firstLine) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(firstLine),
                textBlockArguments.expressionLambdaMethodCallBodyArguments(methodCall, methodCallArgumentList),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Reports whether reconstructing the call opener from compact text would drop a line comment the body carries.
     *
     * <p>The opener line is built from {@code methodCallPrefix} — the call scope and selector compacted to a single line —
     * which strips comments, while only the argument list is rendered through a comment-preserving path. A line comment
     * that sits between the body chain's scope and selector (the issue #94 shape, {@code Optional.of(x) // note .map(y)})
     * therefore lives outside every argument subtree and would be silently dropped by this opener shape. Detecting that
     * lets the caller fall through to the comment-preserving renderers (the source-multiline header shape and the broken
     * body fallback, both of which render the body through the full chain printer). A comment that lies inside an argument
     * is unaffected, so a body whose only comments are nested in a lambda argument keeps the compact opener layout.
     */
    private boolean openerWouldDropPrefixComment(MethodCallExpr methodCall) {
        return methodCall.getAllContainedComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .anyMatch(comment -> !commentLiesInsideAnyArgument(methodCall, comment));
    }

    private boolean commentLiesInsideAnyArgument(MethodCallExpr methodCall, Comment comment) {
        return methodCall.getArguments()
                .stream()
                .anyMatch(argument -> argument.getAllContainedComments().stream().anyMatch(contained -> contained == comment)
                        || argument.getComment().filter(own -> own == comment).isPresent());
    }

    Optional<Doc> methodCallBodyWithHeader(String parameters, MethodCallExpr methodCall) {
        return methodCallBodies.bodyWithHeader(parameters, methodCall);
    }

    /**
     * Renders a binary lambda body as {@code parameters -> methodCall(} when splitting that call operand is enough.
     */
    Optional<Doc> binaryMethodCallBodyWithOpener(String parameters, BinaryExpr binaryExpr) {
        Optional<MethodCallExpr> methodCallCandidate = binaryMethodCallLeftOperand(binaryExpr);
        if (methodCallCandidate.isEmpty()) {
            return Optional.empty();
        }
        MethodCallExpr methodCall = methodCallCandidate.orElseThrow();
        String opener = methodCallPrefix(methodCall) + "(";
        String firstLine = parameters + " -> " + opener;
        if (
            expressionFirstLineWidth(firstLine) > options.lineWidth()
            || brokenArgumentListLambdaBodyWidth(firstLine) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        String operator = binaryExpr.getOperator().asString();
        Expression right = binaryExpr.getRight();
        String inlineClosingLine = ") " + operator + " " + compact.apply(right);
        boolean tailFitsClosingLine = expressionFirstLineWidth(inlineClosingLine) <= options.lineWidth();
        String closingLine = tailFitsClosingLine ? inlineClosingLine : ")";
        Doc closedCall = Doc.concat(
            Doc.text(firstLine),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(closingLine)
        );
        if (tailFitsClosingLine) {
            return Optional.of(closedCall);
        }
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                closedCall,
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text(operator + " "),
                        expressionRenderer.format(right, LayoutContext.root())
                    )
                )
            )
        );
    }

    /**
     * Renders a method-call argument list containing a single eligible expression lambda.
     */
    Optional<Doc> huggableMethodCallArguments(String prefix, NodeList<Expression> arguments) {
        return huggableMethodCallArguments(prefix, arguments, this::expressionFirstLineWidth);
    }

    /**
     * Column-carrying overload of {@link #huggableMethodCallArguments(String, NodeList)}.
     *
     * <p>D3 keystone (see {@code docs/proposals/layout-decision-model.md}): {@code columnWidth} carries the true segment
     * column — the width oracle the chain-segment call-site threads (its {@code compactSegmentWidth}), measuring at the
     * real fanned continuation column rather than the fixed shallow baseline the internal probes assume. It is
     * threaded-but-NOT-consulted here (byte-identical): every body-shape probe below still decides flat-vs-break with the
     * fixed-budget {@link #expressionFirstLineWidth} / {@code nodeIndentWidth}-based expressions exactly as before, and the
     * 2-arg form above defaults {@code columnWidth} to {@link #expressionFirstLineWidth} so
     * {@code columnWidth.applyAsInt(text)} reproduces today's value. Consuming it — routing the hug body-shape gates
     * ({@link #openerOverflows}, {@link #compactBodyWithClosingLine}, the fan probes) through the true column — is the
     * atomic D3 flip, out of scope for this slice.
     */
    Optional<Doc> huggableMethodCallArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> columnWidth
    ) {
        Optional<Plan> huggable = plan(prefix, arguments, LayoutContext.root(), columnWidth);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        Plan argument = huggable.orElseThrow();
        LambdaExpr lambdaExpr = argument.lambdaExpr();
        Optional<LambdaExpr> nestedLambda = argument.nestedLambda();
        String firstLine = argument.firstLine();
        Expression bodyExpression = argument.bodyExpression();
        if (nestedLambda.isPresent()) {
            Optional<Doc> nestedMethodCallBody = nestedLambda.orElseThrow()
                    .getExpressionBody()
                    .filter(MethodCallExpr.class::isInstance)
                    .map(MethodCallExpr.class::cast)
                    .flatMap(methodCall -> methodCallBodyWithOpener(
                            lambdaParameters.apply(nestedLambda.orElseThrow()),
                            methodCall,
                            columnWidth
                    ));
            if (nestedMethodCallBody.isPresent() && !bodyFirstSourceLineFits(firstLine, bodyExpression)) {
                return Optional.of(
                    Doc.concat(
                        Doc.text(prefix + "("),
                        Doc.indent(
                            Doc.concat(
                                Doc.HARD_LINE,
                                Doc.text(argument.parameters() + " ->"),
                                Doc.indent(Doc.concat(Doc.HARD_LINE, nestedMethodCallBody.orElseThrow()))
                            )
                        ),
                        Doc.HARD_LINE,
                        Doc.text(")")
                    )
                );
            }
            Doc bodyDoc = huggableExpressionLambdaBody(firstLine, bodyExpression);
            if (bodyFirstSourceLineFits(firstLine, bodyExpression)) {
                return Optional.of(
                    Doc.concat(
                        Doc.text(prefix + "("),
                        Doc.indent(
                            Doc.concat(
                                Doc.HARD_LINE,
                                Doc.text(lambdaFirstLine(lambdaExpr, argument.parameters()) + " "),
                                Doc.indent(bodyDoc)
                            )
                        ),
                        Doc.HARD_LINE,
                        Doc.text(")")
                    )
                );
            }
            return Optional.of(
                Doc.concat(
                    Doc.text(prefix + "("),
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            Doc.text(lambdaFirstLine(lambdaExpr, argument.parameters())),
                            Doc.indent(
                                Doc.concat(
                                    Doc.HARD_LINE,
                                    bodyDoc
                                )
                            )
                        )
                    ),
                    Doc.HARD_LINE,
                    Doc.text(")")
                )
            );
        }
        Doc bodyDoc = huggableExpressionLambdaBody(firstLine, bodyExpression);
        Optional<Doc> negatedLogicalBody = negatedLogicalBodyWithOpener(firstLine, bodyExpression);
        if (negatedLogicalBody.isPresent()) {
            return negatedLogicalBody;
        }
        if (logicalBinaryBody(bodyExpression).isPresent()) {
            if (logicalBinaryFirstLineFits(firstLine, bodyExpression)) {
                if (closingLayout.callClosingStaysOnLambdaBodyLine(lambdaExpr, bodyExpression)) {
                    return Optional.of(
                        Doc.concat(
                            Doc.text(firstLine + " "),
                            bodyDoc,
                            Doc.text(")")
                        )
                    );
                }
                return Optional.of(
                    Doc.concat(
                        Doc.text(firstLine + " "),
                        Doc.indent(bodyDoc),
                        Doc.HARD_LINE,
                        Doc.text(")")
                    )
                );
            }
            return Optional.of(
                Doc.concat(
                    Doc.text(firstLine),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, bodyDoc)),
                    Doc.HARD_LINE,
                    Doc.text(")")
                )
            );
        }
        // Canonical-fan cutover seam (End-state A), the lambda-body position (U7). A method-call chain that IS a lambda
        // body and reaches the structural fan threshold ({@code lambdaBodyChainFansByCanonicalRule}) fans one selector per
        // line — root on the {@code ->} line, each {@code .call(...)} on its own continuation line — the same
        // source-neutral shape every other chain position already cuts over to, even when the flat body would fit. This
        // runs BEFORE the fitting {@code compactBodyWithClosingLine} shape so a fan-threshold chain fans instead of staying
        // flat, matching gjf/prettier's "one segment per line once the chain is a builder" convention. Comment-bearing,
        // block-lambda, and lambda-arrow (attachable expression-lambda-body) chains are withheld by the shared canonical
        // rule; object-creation-rooted chains ({@code new X().setA(...)}) are additionally withheld by the lambda-body gate
        // because the hugged fan renders that root at column zero and would oscillate the {@code new X()} hug across passes
        // (the deferred nested-root slice). All withheld shapes fall through to the unchanged layouts below.
        if (
            bodyExpression instanceof MethodCallExpr chainBody
            && lambdaBodyChainFansByCanonicalRule.test(chainBody)
        ) {
            Optional<Doc> canonicalFan = huggedLambdaBodyChain(firstLine, chainBody);
            if (canonicalFan.isPresent()) {
                return canonicalFan;
            }
        }
        Optional<Doc> compactBody = compactBodyWithClosingLine(firstLine, bodyExpression, columnWidth);
        if (compactBody.isPresent()) {
            return compactBody;
        }
        if (
            bodyExpression instanceof MethodCallExpr chainBody
            && overflowingHuggedBareRootChainBody(firstLine, chainBody, columnWidth)
        ) {
            Optional<Doc> huggedChain = huggedLambdaBodyChain(firstLine, chainBody);
            if (huggedChain.isPresent()) {
                return huggedChain;
            }
        }
        Optional<PackedLambdaBody> packedBody = packedLambdaBody(lambdaExpr, firstLine, bodyExpression, columnWidth);
        if (packedBody.isPresent()) {
            return Optional.of(packedBody.orElseThrow().render(firstLine));
        }
        if (bodyFirstSourceLineFits(firstLine, bodyExpression)) {
            return Optional.of(
                Doc.concat(
                    Doc.text(firstLine + " "),
                    Doc.indent(bodyDoc),
                    Doc.HARD_LINE,
                    Doc.text(")")
                )
            );
        }
        if (
            bodyExpression instanceof MethodCallExpr methodCall
            && methodCallBodyWithOpener(argument.parameters(), methodCall, columnWidth).isPresent()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(firstLine),
                Doc.indent(Doc.concat(Doc.HARD_LINE, bodyDoc)),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Renders a lambda-body method-call chain fanned onto dotted continuation lines while it hugs the lambda header on the
     * first line ({@code call(handler -> assertThat(handler)}⏎{@code .extracting(...)}⏎{@code .containsOnly(...))}), then
     * dedents the enclosing call close to its own line.
     *
     * <p>The fan itself is built by {@code huggedLambdaBodyChainRenderer} — the shared method-chain printer, which threads
     * {@code firstLine + " "} as the chain's {@link LayoutContext#leftEdgePrefix()} so every width gate measures at the
     * real rendered column and re-derives the identical source-neutral fan across passes. Both the canonical-fan cutover
     * seam (End-state A, fan a fan-threshold chain even when it fits) and the historical over-width bare-root branch reuse
     * this one shape so the two triggers produce byte-identical layouts for the chains they share.
     *
     * <p>The close dedents to its own line at the opener's column, the same shape a broken argument list renders
     * ({@code foo(}⏎{@code arg}⏎{@code )}) and the packed lambda-body shapes' {@code PackedLambdaBody.CLOSING_ON_OWN_LINE}
     * produce. The fanned chain already carries its own continuation indent, so the {@code HARD_LINE} + close stay outside
     * any extra indent and land back at the enclosing statement's column; a lambda header opening several calls before the
     * break would stack their closes on this one dedented line.
     */
    private Optional<Doc> huggedLambdaBodyChain(String firstLine, MethodCallExpr chainBody) {
        return huggedLambdaBodyChainRenderer.apply(firstLine, chainBody)
                .map(huggedChain -> Doc.concat(
                        Doc.text(firstLine + " "),
                        huggedChain,
                        Doc.HARD_LINE,
                        Doc.text(")")
                ));
    }

    /**
     * Builds the shared expression-lambda argument plan used by call and chain printers for width decisions.
     *
     * <p>D1g (#190) threads {@code layout} so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available to this lambda-hug admission gate for the eventual reflow-by-width flip. It is NOT yet consulted: the
     * entry gate below still decides admit-vs-withhold with the fixed-budget {@link #expressionFirstLineWidth} and
     * {@code nodeIndentWidth}-based {@link #expressionLineWidth} probes exactly as before, so threading it is
     * byte-identical. The external callers (return / assignment / initializer / statement-call and single-segment-root
     * positions) already thread a real {@link LayoutContext} — carrying the {@code "return "} / {@code NAME = } /
     * segment prefix — into their own Plan-consuming first-line predicates ({@code methodCallRootLineWidth},
     * {@code compactRootLineWidth}); this makes the SAME context available to the plan's own internal admission probe so
     * it can measure at the identical rendered column once C10 activates. The internal caller
     * ({@link #huggableMethodCallArguments}) passes {@link LayoutContext#root()}: it owns the hug body-shape column
     * decisions ({@link #openerOverflows}, {@link #compactBodyWithClosingLine}, the fan probes) separately from this
     * admission gate, and its own chain-segment / attach entry points are the chain-track / D2d-owned positions whose
     * true column is a segment column, not a {@code leftEdgePrefix} (see the flip-map).
     */
    Optional<Plan> plan(String prefix, NodeList<Expression> arguments) {
        return plan(prefix, arguments, LayoutContext.root());
    }

    Optional<Plan> plan(String prefix, NodeList<Expression> arguments, LayoutContext layout) {
        return plan(prefix, arguments, layout, this::expressionFirstLineWidth);
    }

    /**
     * Column-carrying overload of {@link #plan(String, NodeList, LayoutContext)}.
     *
     * <p>D3 keystone: {@code columnWidth} carries the true segment column the chain-segment call-site threads (its
     * {@code compactSegmentWidth}), so the eventual reflow-by-width flip can admit-vs-withhold and shape the hug body at
     * the real fanned continuation column. It is threaded-but-NOT-consulted (byte-identical): the admission gate below
     * still decides with the fixed-budget {@link #expressionFirstLineWidth} and {@code nodeIndentWidth}-based
     * {@link #expressionLineWidth} probes exactly as before, and the 3-arg form defaults {@code columnWidth} to
     * {@link #expressionFirstLineWidth}. It is threaded on to the body-shape probes ({@link #methodCallBodyWithOpener},
     * the {@code packed*} openers) purely so they carry the same oracle. Consuming it is the atomic D3 flip, out of scope
     * for this slice — this seam is distinct from the D1g {@link LayoutContext} plumbing, which threads the
     * {@code leftEdgePrefix} column instead.
     */
    Optional<Plan> plan(
            String prefix,
            NodeList<Expression> arguments,
            LayoutContext layout,
            ToIntFunction<String> columnWidth
    ) {
        int lambdaIndex = expressionLambdaArgumentIndex(arguments);
        if (
            lambdaIndex < 0
            || lambdaIndex < arguments.size() - 1
            || hasOtherLambdaArgument(arguments, lambdaIndex)
        ) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = (LambdaExpr) arguments.get(lambdaIndex);
        Optional<Expression> body = lambdaExpr.getExpressionBody();
        Optional<LambdaExpr> nestedLambda = body
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast);
        if (
            body.isEmpty()
            || !lambdaExpr.getAllContainedComments().isEmpty()
        ) {
            return Optional.empty();
        }
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (lambdaParametersShouldBreak.test(lambdaExpr, parameters)) {
            return Optional.empty();
        }
        if (nestedLambda.isPresent()) {
            LambdaExpr nested = nestedLambda.orElseThrow();
            if (
                !nested.getAllContainedComments().isEmpty()
                || lambdaParametersShouldBreak.test(nested, lambdaParameters.apply(nested))
            ) {
                return Optional.empty();
            }
        }
        String leadingArguments = compactJoin.apply(arguments.subList(0, lambdaIndex));
        String lambdaFirstLine = lambdaFirstLine(lambdaExpr, parameters);
        String firstLine = prefix
            + "("
            + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
            + lambdaFirstLine;
        String flat = prefix + "(" + compactJoin.apply(arguments) + ")";
        boolean sourceMultilineBody = body.filter(this::sourceMultilineLogicalBody).isPresent()
            || body.filter(this::sourceMultilineMethodCallBody).isPresent()
            || body.filter(bodyExpression -> bodyStartsAfterLambdaHeader(lambdaExpr, bodyExpression)).isPresent();
        if (
            (!sourceMultilineBody
                && expressionFirstLineWidth(flat) < options.lineWidth())
            || expressionLineWidth(firstLine, lambdaExpr, lambdaFirstLine) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        Optional<Expression> bodyExpressionCandidate = huggableBodyExpression(lambdaExpr);
        if (bodyExpressionCandidate.isEmpty()) {
            return Optional.empty();
        }
        Expression bodyExpression = bodyExpressionCandidate.orElseThrow();
        if (
            !huggableBody(bodyExpression)
            && !huggableOverflowingMethodCallBody(firstLine, bodyExpression)
        ) {
            return Optional.empty();
        }
        Plan plan = new Plan(
            lambdaExpr,
            nestedLambda,
            bodyExpression,
            parameters,
            firstLine,
            bodyFirstSourceLineFits(firstLine, bodyExpression),
            lambdaBodyOpenerLine(parameters, bodyExpression),
            callBodyOpenerLine(prefix, leadingArguments, parameters, bodyExpression)
        );
        if (
            bodyExpression instanceof MethodCallExpr methodCall
            && methodCallBodyWithOpener(parameters, methodCall, columnWidth).isPresent()
            && packedBodyCallWithoutClosingLine(lambdaExpr, firstLine, bodyExpression, columnWidth).isEmpty()
            && packedBodyCallScopeWithoutClosingLine(lambdaExpr, firstLine, bodyExpression, columnWidth).isEmpty()
            && !bodyFirstSourceLineFits(firstLine, bodyExpression)
        ) {
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    boolean sourceMultilineLogicalBody(Expression body) {
        return logicalBinaryBody(body).isPresent() && sourceShapePolicy.wasMultiline(body);
    }

    boolean sourceMultilineMethodCallBody(Expression body) {
        return (
            body instanceof MethodCallExpr methodCall
            && (sourceShapePolicy.wasMultiline(methodCall)
                || methodCall.getScope()
                        .filter(sourceShapePolicy::wasMultiline)
                        .isPresent())
        );
    }

    private boolean bodyStartsAfterLambdaHeader(LambdaExpr lambdaExpr, Expression bodyExpression) {
        return lambdaExpr.getRange()
                .flatMap(lambdaRange -> bodyExpression.getRange().map(
                        bodyRange -> bodyRange.begin.line > lambdaRange.begin.line
                ))
                .orElse(false);
    }

    private Doc huggableExpressionLambdaBody(String firstLine, Expression bodyExpression) {
        Optional<Doc> logicalBody = logicalBinaryBodyDoc(bodyExpression);
        if (logicalBody.isPresent()) {
            return logicalBody.orElseThrow();
        }
        if (
            bodyExpression instanceof MethodCallExpr methodCall
            && methodCall.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
            && (sourceMultilineMethodCallBody(methodCall)
                || bodyFirstSourceLineOverflows(firstLine, methodCall)
                || bodyCompactLineOverflows(firstLine, methodCall))
        ) {
            return brokenMethodCallRenderer.apply(methodCall);
        }
        if (
            bodyExpression instanceof ObjectCreationExpr objectCreation
            && expressionFirstLineWidth(firstLine + " " + compact.apply(objectCreation)) > options.lineWidth()
        ) {
            return brokenObjectCreationRenderer.apply(objectCreation);
        }
        return expressionRenderer.format(bodyExpression, LayoutContext.root());
    }

    /**
     * Reports whether a lambda body is a bare-call-rooted method-call chain whose flat form overflows the line at the
     * lambda header's <em>real rendered column</em>, so it should fan onto dotted continuation lines while hugging the
     * lambda header ({@code someCall(x -> assertThat(x)}\n{@code .extracting(...)}\n{@code .containsOnly(...))}).
     *
     * <p>The gate is deliberately narrow so it moves only genuine {@code #221} Case-A chains and leaves every other
     * lambda-body shape to the existing opener-packing and greedy-pack paths:
     *
     * <ul>
     *   <li><strong>It is a chain.</strong> The receiver is itself a method call, so there is at least one {@code .call(...)}
     *   selector to fan below the root. A single-call body ({@code x -> foo.bar(arg)}) has a non-call receiver and is left
     *   to the opener-packing shapes that break only the argument list.</li>
     *   <li><strong>Its root is a bare call.</strong> The innermost receiver is an unscoped method call
     *   ({@code assertThat(x)}), which {@link #packedMethodCallChainBody} cannot fan — its greedy packer requires a
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
    private boolean overflowingHuggedBareRootChainBody(
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

    private boolean chainRootIsBareCall(MethodCallExpr methodCall) {
        Optional<Expression> scope = methodCall.getScope();
        if (scope.filter(MethodCallExpr.class::isInstance).isPresent()) {
            return chainRootIsBareCall((MethodCallExpr) scope.orElseThrow());
        }
        return scope.isEmpty();
    }

    private boolean chainOverflowsHuggedColumn(
            String firstLine,
            MethodCallExpr methodCall,
            ToIntFunction<String> columnWidth
    ) {
        // D3 keystone: {@code columnWidth} (the true segment column) is threaded-but-NOT-consulted; this probe still
        // measures at the {@code nodeIndentWidth}-based rendered column exactly as before. Consuming it is the D3 flip.
        return layoutWidth.nodeIndentWidth(methodCall) + firstLine.length() + 1 + compact.apply(methodCall).length()
            > options.lineWidth();
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
        // D3 keystone: {@code columnWidth} (the true segment column) is threaded-but-NOT-consulted; every width gate
        // below still measures at the {@code nodeIndentWidth}-based rendered column exactly as before. The D3 flip is
        // what will route these gates through the true segment column.
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
        return "." + methodCallSelector(segment) + "(" + compactJoin.apply(segment.getArguments()) + ")";
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
                        || !argument.getAllContainedComments().isEmpty()
                        || sourceShapePolicy.wasMultiline(argument));
    }

    private Optional<Doc> compactBodyWithClosingLine(
            String firstLine,
            Expression bodyExpression,
            ToIntFunction<String> columnWidth
    ) {
        // D3 keystone: {@code columnWidth} (the true segment column) is threaded-but-NOT-consulted; the closing-line fit
        // below still measures at the fixed {@code LAMBDA_ARGUMENT_CLOSING} budget exactly as before. The D3 flip is
        // what will measure this compact body-with-closing-line at the true segment column.
        if (sourceMultilineBinaryMethodCallBody(bodyExpression)) {
            return Optional.empty();
        }
        String line = firstLine + " " + compact.apply(bodyExpression) + ")";
        if (layoutWidth.line(LayoutWidth.LineBudget.LAMBDA_ARGUMENT_CLOSING, line) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(line));
    }

    private int brokenArgumentListLambdaBodyWidth(String bodyLine) {
        return layoutWidth.line(LayoutWidth.LineBudget.METHOD_CHAIN_LAMBDA_BODY, bodyLine);
    }

    /**
     * Picks the first body shape that can share the lambda opener and records who owns the closing call suffix.
     */
    private Optional<PackedLambdaBody> packedLambdaBody(
            LambdaExpr lambdaExpr,
            String firstLine,
            Expression bodyExpression,
            ToIntFunction<String> columnWidth
    ) {
        return packedObjectCreationWithoutClosingLine(lambdaExpr, firstLine, bodyExpression, columnWidth)
                .map(doc -> PackedLambdaBody.closingOnOwnLine(doc, "))"))
                .or(() -> packedBodyCallWithBlockLambda(lambdaExpr, firstLine, bodyExpression, columnWidth).map(
                        doc -> PackedLambdaBody.attachedClosing(doc, "))")
                ))
                .or(() -> packedConditionalBody(firstLine, bodyExpression).map(
                        doc -> PackedLambdaBody.closingOnOwnLine(doc, ")")
                ))
                .or(() -> packedBodyCallWithoutClosingLine(lambdaExpr, firstLine, bodyExpression, columnWidth).map(
                        doc -> PackedLambdaBody.closingOnOwnLine(doc, "))")
                ))
                .or(() -> packedMethodCallChainBody(firstLine, bodyExpression).map(
                        doc -> PackedLambdaBody.closingOnOwnLine(doc, ")")
                ))
                .or(() -> packedBodyEmptyCallScope(lambdaExpr, firstLine, bodyExpression, columnWidth).map(
                        doc -> PackedLambdaBody.closingOnOwnLine(doc, ")")
                ))
                .or(() -> packedBodyCallScopeWithoutClosingLine(lambdaExpr, firstLine, bodyExpression, columnWidth).map(
                        doc -> PackedLambdaBody.closingOnOwnLine(doc, "))")
                ));
    }

    private Optional<Doc> packedMethodCallChainBody(String firstLine, Expression bodyExpression) {
        if (!(bodyExpression instanceof MethodCallExpr methodCall)) {
            return Optional.empty();
        }
        return packedMethodCallChainBodyRenderer.apply(firstLine, methodCall);
    }

    private Optional<Doc> packedConditionalBody(String firstLine, Expression bodyExpression) {
        if (
            !(bodyExpression instanceof ConditionalExpr conditionalExpr)
            || !conditionalExpr.getAllContainedComments().isEmpty()
        ) {
            return Optional.empty();
        }
        String condition = compact.apply(conditionalExpr.getCondition());
        if (expressionFirstLineWidth(firstLine + " " + condition) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(condition),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text("? "),
                        expressionRenderer.format(conditionalExpr.getThenExpr(), LayoutContext.root()),
                        Doc.HARD_LINE,
                        Doc.text(": "),
                        expressionRenderer.format(conditionalExpr.getElseExpr(), LayoutContext.root())
                    )
                )
            )
        );
    }

    private Optional<Doc> packedBodyCallWithoutClosingLine(
            LambdaExpr lambdaExpr,
            String firstLine,
            Expression bodyExpression,
            ToIntFunction<String> columnWidth
    ) {
        if (
            !(bodyExpression instanceof MethodCallExpr methodCall)
            || methodCall.getArguments().isEmpty()
            || methodCall.getScope().filter(sourceShapePolicy::wasMultiline).isPresent()
        ) {
            return Optional.empty();
        }
        String opener = methodCallPrefix(methodCall) + "(";
        if (openerOverflows(lambdaExpr, firstLine + " " + opener, columnWidth)) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(opener),
                textBlockArguments.expressionLambdaMethodCallBodyArguments(methodCall, methodCallArgumentList)
            )
        );
    }

    private Optional<Doc> packedBodyCallWithBlockLambda(
            LambdaExpr outerLambda,
            String firstLine,
            Expression bodyExpression,
            ToIntFunction<String> columnWidth
    ) {
        if (
            !(bodyExpression instanceof MethodCallExpr methodCall)
            || methodCall.getScope().filter(sourceShapePolicy::wasMultiline).isPresent()
        ) {
            return Optional.empty();
        }
        String opener = methodCallPrefix(methodCall) + "(";
        if (
            methodCall.getArguments().size() != 1
            || !(methodCall.getArguments().getFirst().orElseThrow() instanceof LambdaExpr lambdaExpr)
            || !lambdaExpr.getBody().isBlockStmt()
            || !lambdaExpr.getAllContainedComments().isEmpty()
        ) {
            return Optional.empty();
        }
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (
            lambdaParametersShouldBreak.test(lambdaExpr, parameters)
            || openerOverflows(outerLambda, firstLine + " " + opener + parameters + " -> {", columnWidth)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(opener + parameters + " -> {"),
                Doc.indent(
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            Doc.join(
                                Doc.HARD_LINE,
                                lambdaExpr.getBody()
                                        .asBlockStmt()
                                        .getStatements()
                                        .stream()
                                        .map(node -> statementRenderer.format(node, LayoutContext.root()))
                                        .toList()
                            )
                        )
                    )
                ),
                Doc.HARD_LINE,
                Doc.text("}")
            )
        );
    }

    private Optional<Doc> packedObjectCreationWithoutClosingLine(
            LambdaExpr lambdaExpr,
            String firstLine,
            Expression bodyExpression,
            ToIntFunction<String> columnWidth
    ) {
        if (
            !(bodyExpression instanceof ObjectCreationExpr objectCreation)
            || objectCreation.getArguments().isEmpty()
        ) {
            return Optional.empty();
        }
        String opener = objectCreationPrefix(objectCreation) + "(";
        if (openerOverflows(lambdaExpr, firstLine + " " + opener, columnWidth)) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(opener),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(objectCreation.getArguments(), Doc.HARD_LINE)
                    )
                )
            )
        );
    }

    private Optional<Doc> packedBodyCallScopeWithoutClosingLine(
            LambdaExpr lambdaExpr,
            String firstLine,
            Expression bodyExpression,
            ToIntFunction<String> columnWidth
    ) {
        if (
            !(bodyExpression instanceof MethodCallExpr methodCall)
            || methodCall.getScope().isEmpty()
        ) {
            return Optional.empty();
        }
        String scope = compact.apply(methodCall.getScope().orElseThrow());
        String bodyFirstLine = bodyFirstSourceLine(bodyExpression);
        if (!bodyFirstLine.endsWith("(") && !bodyFirstLine.equals(scope)) {
            return Optional.empty();
        }
        if (openerOverflows(lambdaExpr, firstLine + " " + scope, columnWidth)) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(scope),
                Doc.HARD_LINE,
                Doc.text("." + methodCallSelector(methodCall) + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)
                    )
                )
            )
        );
    }

    private Optional<Doc> packedBodyEmptyCallScope(
            LambdaExpr lambdaExpr,
            String firstLine,
            Expression bodyExpression,
            ToIntFunction<String> columnWidth
    ) {
        if (
            !(bodyExpression instanceof MethodCallExpr methodCall)
            || methodCall.getScope().isEmpty()
            || !methodCall.getArguments().isEmpty()
        ) {
            return Optional.empty();
        }
        String scope = compact.apply(methodCall.getScope().orElseThrow());
        String bodyFirstLine = bodyFirstSourceLine(bodyExpression);
        if (!bodyFirstLine.equals(scope)) {
            return Optional.empty();
        }
        String compactCall = scope + "." + methodCallSelector(methodCall) + "()";
        if (!openerOverflows(lambdaExpr, firstLine + " " + compactCall, columnWidth)) {
            return Optional.of(Doc.text(compactCall));
        }
        if (openerOverflows(lambdaExpr, firstLine + " " + scope, columnWidth)) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(scope),
                Doc.HARD_LINE,
                Doc.text("." + methodCallSelector(methodCall) + "()")
            )
        );
    }

    private Optional<Doc> negatedLogicalBodyWithOpener(String firstLine, Expression bodyExpression) {
        Optional<BinaryExpr> logicalBody = negatedLogicalBinaryBody(bodyExpression);
        if (logicalBody.isEmpty()) {
            return Optional.empty();
        }
        String opener = firstLine + " !(";
        if (expressionFirstLineWidth(opener) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(opener),
                Doc.indent(Doc.concat(
                    Doc.HARD_LINE,
                    binaryExpressionNestedLinesRenderer.apply(logicalBody.orElseThrow(), true)
                )),
                Doc.HARD_LINE,
                Doc.text("))")
            )
        );
    }

    private Optional<Doc> logicalBinaryBodyDoc(Expression body) {
        return logicalBinaryBody(body).map(binary -> {
            Doc lines = binaryExpressionNestedLinesRenderer.apply(binary, true);
            for (int i = 0; i < enclosedDepth(body); i++) {
                lines = Doc.concat(Doc.text("("), lines, Doc.text(")"));
            }
            return lines;
        });
    }

    /**
     * Single expression-lambda argument hugs its call opener (gjf/prettier-java) for a LOGICAL BINARY body: builds the
     * opener-hugged broken layout for a fanned chain selector whose sole argument is an expression lambda whose body is a
     * {@code &&}/{@code ||} chain, keeping {@code .selector(param -> <first operand>} on the selector line and stacking each
     * following operand one per line below it, then dedenting the enclosing {@code )} to its own line at the selector column:
     *
     * <pre>{@code
     * .map(region -> region.beginOffset() == expected.beginOffset()
     *         && region.endOffset() == expected.endOffset()
     * )
     * }</pre>
     *
     * <p>This is the BINARY sibling of the round-2 source-neutral TERNARY hug ({@code packedConditionalBody}). It is built
     * DIRECTLY here — reusing the source-neutral {@link #logicalBinaryBodyDoc} render (a pure {@code nestedLines} function of
     * the AST) and always dedenting the close ({@link PackedLambdaBody#closingOnOwnLine}) — rather than routing through the
     * shared {@code plan}/{@link #huggableMethodCallArguments} path the way the round-1 object-creation / round-2 ternary hug
     * do. The shared path carries two source-shape reads that flip this shape across passes and are why review round 2
     * DROPPED the binary hug: {@code plan}'s {@code sourceMultilineBody} entry gate (a single-line-flat body withholds the
     * hug on pass 1, then the re-formatted multiline body admits it on pass 2), and
     * {@code ExpressionLambdaClosingLayout#callClosingStaysOnLambdaBodyLine} (attaches vs. dedents the close by whether the
     * source put {@code )} on the body's last line). Bypassing both makes this a fixpoint: the render is a pure function of
     * the AST and the close placement is fixed. The separately-gated {@code binaryMethodCallBodyWithOpener}
     * (the {@code sourceMultilineBinaryMethodCallBody} path that oscillated on {@code .map(x -> x.f(a) == ALLOWED)} in kafka
     * {@code AuthHelper}) is never touched.
     *
     * <p>Scoped to LOGICAL ({@code &&}/{@code ||}) bodies: a top-level RELATIONAL body ({@code x -> f(...) == ALLOWED}) is not
     * a {@link #logicalBinaryBody} and is left unclaimed, so the {@code AuthHelper} shape — whose left operand is a wide
     * method call the base layout breaks by its argument list — keeps its existing broken-segment shape and does not
     * oscillate. Guarded on the FIRST flattened operand fitting the opener line at the lambda's real rendered column
     * ({@link #openerOverflows}, a pure-AST depth-aware probe like {@code packedConditionalBody}'s condition-fits guard), so a
     * logical body whose first operand is itself too wide to hug is withheld rather than hugged over-width. Returns empty for
     * a comment-bearing body so no comment is dropped. The caller wraps the result as the broken arm of the selector's
     * {@link Doc#conditionalGroup}, so the flat {@code .selector(param -> a == b)} still renders when it fits and this hugged
     * form renders only when it does not — the true-column flat-vs-broken decision stays with that conditional group.
     */
    Optional<Doc> logicalBinaryLambdaBodyOpenerHug(String prefix, MethodCallExpr expression) {
        return logicalBinaryLambdaBodyOpenerHug(prefix, expression, this::expressionFirstLineWidth);
    }

    /**
     * Column-carrying overload of {@link #logicalBinaryLambdaBodyOpenerHug(String, MethodCallExpr)}.
     *
     * <p>D3 keystone: {@code columnWidth} carries the true segment column the chain-segment call-site threads. It is
     * threaded-but-NOT-consulted (byte-identical): the first-operand-fits guard below still uses the depth-aware
     * {@link #openerOverflows} probe exactly as before, and the 2-arg form defaults {@code columnWidth} to
     * {@link #expressionFirstLineWidth}. Consuming it — measuring the opener at the true segment column — is the atomic
     * D3 flip, out of scope for this slice.
     */
    Optional<Doc> logicalBinaryLambdaBodyOpenerHug(
            String prefix,
            MethodCallExpr expression,
            ToIntFunction<String> columnWidth
    ) {
        NodeList<Expression> arguments = expression.getArguments();
        if (arguments.size() != 1 || !(arguments.get(0) instanceof LambdaExpr lambdaExpr)) {
            return Optional.empty();
        }
        Optional<Expression> body = lambdaExpr.getExpressionBody();
        if (body.isEmpty() || !lambdaExpr.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        Expression bodyExpression = body.orElseThrow();
        Optional<BinaryExpr> logicalBody = logicalBinaryBody(bodyExpression);
        if (logicalBody.isEmpty() || !bodyExpression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        Optional<Doc> bodyLines = logicalBinaryBodyDoc(bodyExpression);
        if (bodyLines.isEmpty()) {
            return Optional.empty();
        }
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (lambdaParametersShouldBreak.test(lambdaExpr, parameters)) {
            return Optional.empty();
        }
        // {@code firstLine} carries no trailing space: {@link PackedLambdaBody#render} rejoins it as {@code firstLine + " "},
        // gluing the first operand after {@code ->} the same way the shared renderer's {@code lambdaFirstLine} does.
        String firstLine = prefix + "(" + parameters + " ->";
        String firstOperand = compact.apply(firstBinaryOperand(logicalBody.orElseThrow()));
        if (openerOverflows(lambdaExpr, firstLine + " " + firstOperand, columnWidth)) {
            return Optional.empty();
        }
        return Optional.of(
            PackedLambdaBody.closingOnOwnLine(bodyLines.orElseThrow(), ")").render(firstLine)
        );
    }

    private boolean isLogicalBinaryOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR;
    }

    private Optional<BinaryExpr> logicalBinaryBody(Expression body) {
        if (body instanceof BinaryExpr binaryExpr && isLogicalBinaryOperator(binaryExpr)) {
            return Optional.of(binaryExpr);
        }
        if (body instanceof EnclosedExpr enclosedExpr) {
            return logicalBinaryBody(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    private Optional<BinaryExpr> negatedLogicalBinaryBody(Expression body) {
        if (
            body instanceof UnaryExpr unaryExpr
            && unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && unaryExpr.getExpression() instanceof EnclosedExpr enclosedExpr
        ) {
            return logicalBinaryBody(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    private int enclosedDepth(Expression body) {
        int depth = 0;
        Expression current = body;
        while (current instanceof EnclosedExpr enclosedExpr) {
            depth++;
            current = enclosedExpr.getInner();
        }
        return depth;
    }

    private int expressionFirstLineWidth(String firstLine) {
        return blockStatementWidth.applyAsInt(options.indentUnit() + firstLine);
    }

    /**
     * Reports whether a hugged-lambda opener ({@code call(args lambdaHeader -> innerCall(}) would push the call's first
     * line past the line width once it is measured at the call's <em>real</em> rendered column.
     *
     * <p>The opener gates here historically probed {@link #expressionFirstLineWidth}, which assumes a fixed shallow
     * nesting baseline (one block plus an indent unit) and is therefore blind to how deeply the call actually sits. A call
     * nested inside an {@code if}/{@code for} body could attach an opener that visibly overflowed because the extra
     * enclosing levels were never counted, and the over-wide shape was stable (idempotent but wrong). Measuring the opener
     * at the lambda's rendered indentation ({@link LayoutWidth#nodeIndentWidth}, which counts every enclosing type and
     * block) makes the hug-vs-break decision width-deterministic, mirroring the depth-aware first-line probe threaded into
     * method-chain layout (#162) and the prefix/depth-aware single-argument hug gate (#164).
     *
     * <p>The probe takes the wider of the historical baseline and the real rendered column, so it can only ever break a
     * hug that genuinely overflows at its true depth; it never relaxes the gate for shallow calls, keeping fitting hugs
     * unchanged.
     */
    private boolean openerOverflows(LambdaExpr lambdaExpr, String openerLine, ToIntFunction<String> columnWidth) {
        // D3 keystone: {@code columnWidth} (the true segment column) is threaded-but-NOT-consulted; the probe still takes
        // the wider of the fixed baseline and the {@code nodeIndentWidth}-based rendered column exactly as before. The D3
        // flip is what will measure this opener at the true segment column instead.
        int renderedWidth = layoutWidth.nodeIndentWidth(lambdaExpr) + openerLine.length();
        return Math.max(expressionFirstLineWidth(openerLine), renderedWidth) > options.lineWidth();
    }

    /**
     * Measures the call's first line (its prefix, any leading arguments, and the lambda header up to {@code ->}) at the
     * column where the call actually renders.
     *
     * <p>The first-line hug gate at {@link #plan} historically reconstructed that column from the lambda's
     * {@code range.begin.column}: it subtracted the lambda's offset within the assembled first line to recover the
     * prefix's <em>source</em> start column, then added the line length. That reconstruction is only correct while the
     * lambda's source column equals its rendered column. A call nested a few blocks deep — or a source that indented the
     * call more shallowly than the formatter will — makes the source column understate the real one, so an opener that
     * overflowed at its true depth measured as fitting and was hugged over-width; on the next pass the now-deeper source
     * column reported the overflow and the header broke onto its own line, so {@code format(format(x)) != format(x)}
     * (#217). Measuring at the lambda's rendered indentation ({@link LayoutWidth#nodeIndentWidth}, which counts every
     * enclosing type and block) makes the hug-vs-break decision width-deterministic, mirroring the sibling opener probe
     * {@link #openerOverflows} (#165) and the depth-aware method-chain and single-argument gates (#162, #164).
     *
     * <p>The probe takes the wider of the historical fixed baseline and the real rendered column, so it can only ever
     * break a hug that genuinely overflows at its true depth; it never relaxes the gate for shallow calls, keeping
     * fitting hugs unchanged.
     */
    private int expressionLineWidth(String line, LambdaExpr lambdaExpr, String lambdaText) {
        int lambdaOffset = line.indexOf(lambdaText);
        if (lambdaOffset < 0) {
            return expressionFirstLineWidth(line);
        }
        return Math.max(expressionFirstLineWidth(line), layoutWidth.nodeIndentWidth(lambdaExpr) + line.length());
    }

    private boolean bodyFirstSourceLineFits(String firstLine, Expression bodyExpression) {
        return sourceShapePolicy.wasMultiline(bodyExpression)
            && expressionFirstLineWidth(firstLine + " " + bodyFirstSourceLine(bodyExpression)) <= options.lineWidth();
    }

    private boolean logicalBinaryFirstLineFits(String firstLine, Expression bodyExpression) {
        return logicalBinaryFirstLine(bodyExpression)
                .map(bodyFirstLine -> expressionFirstLineWidth(firstLine + " " + bodyFirstLine) <= options.lineWidth())
                .orElse(false);
    }

    private Optional<String> logicalBinaryFirstLine(Expression bodyExpression) {
        if (sourceShapePolicy.wasMultiline(bodyExpression)) {
            return Optional.of(bodyFirstSourceLine(bodyExpression));
        }
        if (bodyExpression instanceof EnclosedExpr enclosedExpr) {
            return logicalBinaryFirstLine(enclosedExpr.getInner()).map(line -> "(" + line);
        }
        return logicalBinaryBody(bodyExpression)
                .map(this::firstBinaryOperand)
                .map(compact);
    }

    private Expression firstBinaryOperand(BinaryExpr binaryExpr) {
        Expression left = binaryExpr.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == binaryExpr.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }

    private boolean bodyFirstSourceLineOverflows(String firstLine, MethodCallExpr methodCall) {
        return expressionFirstLineWidth(firstLine + " " + bodyFirstSourceLine(methodCall)) > options.lineWidth();
    }

    private boolean bodyCompactLineOverflows(String firstLine, MethodCallExpr methodCall) {
        return expressionFirstLineWidth(firstLine + " " + compact.apply(methodCall)) > options.lineWidth();
    }

    private String bodyFirstSourceLine(Node node) {
        return rawSource.rawWithoutOwnComment(node)
                .strip()
                .lines()
                .findFirst()
                .orElse("");
    }

    /**
     * Reports whether an expression-lambda body can share or hug the call's opener line.
     *
     * <p>An object creation with an anonymous class body is deliberately excluded: the packed lambda-body shapes here only
     * reconstruct the constructor opener and arguments and have no place to render the {@code { ... }} member block, so
     * hugging such a body would drop the anonymous class entirely. Excluding it routes the call through the broken
     * argument-list / source-multiline path, which renders the body through the full object-creation printer and stays
     * idempotent regardless of the source line shape.
     */
    private boolean huggableBody(Expression body) {
        if (body instanceof MethodCallExpr methodCall) {
            return !methodCall.getArguments().isEmpty() || sourceMultilineMethodCallBody(methodCall);
        }
        if (body instanceof ObjectCreationExpr objectCreation) {
            return !objectCreation.getArguments().isEmpty()
                && objectCreation.getAnonymousClassBody().isEmpty();
        }
        if (body instanceof ConditionalExpr) {
            return true;
        }
        if (logicalBinaryBody(body).isPresent()) {
            return true;
        }
        if (negatedLogicalBinaryBody(body).isPresent()) {
            return true;
        }
        if (sourceMultilineBinaryMethodCallBody(body)) {
            return true;
        }
        if (body instanceof LambdaExpr lambdaExpr && lambdaExpr.getExpressionBody().isPresent()) {
            return huggableBody(lambdaExpr.getExpressionBody().orElseThrow());
        }
        return false;
    }

    private boolean huggableOverflowingMethodCallBody(String firstLine, Expression body) {
        return body instanceof MethodCallExpr methodCall
            && methodCall.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
            && (bodyFirstSourceLineOverflows(firstLine, methodCall) || bodyCompactLineOverflows(firstLine, methodCall));
    }

    private String lambdaFirstLine(LambdaExpr lambdaExpr, String parameters) {
        return lambdaExpr.getExpressionBody()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .map(nested -> parameters + " -> " + lambdaParameters.apply(nested) + " ->")
                .orElse(parameters + " ->");
    }

    private Optional<Expression> huggableBodyExpression(LambdaExpr lambdaExpr) {
        return lambdaExpr
                .getExpressionBody()
                .flatMap(body -> {
                    if (
                        body instanceof MethodCallExpr
                        || body instanceof ObjectCreationExpr
                        || body instanceof ConditionalExpr
                    ) {
                        return Optional.of(body);
                    }
                    if (logicalBinaryBody(body).isPresent()) {
                        return Optional.of(body);
                    }
                    if (negatedLogicalBinaryBody(body).isPresent()) {
                        return Optional.of(body);
                    }
                    if (sourceMultilineBinaryMethodCallBody(body)) {
                        return Optional.of(body);
                    }
                    if (body instanceof LambdaExpr nested) {
                        return huggableBodyExpression(nested);
                    }
                    return Optional.empty();
                });
    }

    private int expressionLambdaArgumentIndex(NodeList<Expression> arguments) {
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof LambdaExpr lambdaExpr && lambdaExpr.getExpressionBody().isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasOtherLambdaArgument(NodeList<Expression> arguments, int lambdaIndex) {
        for (int i = 0; i < arguments.size(); i++) {
            if (i != lambdaIndex && arguments.get(i) instanceof LambdaExpr) {
                return true;
            }
        }
        return false;
    }

    private String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
            + expression
                    .getTypeArguments()
                    .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    boolean sourceMultilineBinaryMethodCallBody(Expression body) {
        return binaryMethodCallLeftOperand(body)
                .filter(sourceShapePolicy::wasMultiline)
                .isPresent();
    }

    private Optional<MethodCallExpr> binaryMethodCallLeftOperand(Expression body) {
        if (
            !(body instanceof BinaryExpr binaryExpr)
            || !(binaryExpr.getLeft() instanceof MethodCallExpr methodCall)
            || !binaryExpr.getAllContainedComments().isEmpty()
            || methodCall.getArguments().isEmpty()
            || methodCall.getScope().filter(sourceShapePolicy::wasMultiline).isPresent()
        ) {
            return Optional.empty();
        }
        return Optional.of(methodCall);
    }

    private String methodCallSelector(MethodCallExpr expression) {
        return expression.getTypeArguments().map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">").orElse(
            ""
        ) + expression.getNameAsString();
    }

    private String objectCreationPrefix(ObjectCreationExpr expression) {
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
            + "new "
            + expression
                    .getTypeArguments()
                    .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                    .orElse("")
            + compact.apply(expression.getType());
    }

    private String lambdaBodyOpenerLine(String parameters, Expression bodyExpression) {
        if (!(bodyExpression instanceof MethodCallExpr methodCall) || methodCall.getArguments().isEmpty()) {
            return "";
        }
        return parameters + " -> " + methodCallPrefix(methodCall) + "(";
    }

    private String callBodyOpenerLine(
            String prefix,
            String leadingArguments,
            String parameters,
            Expression bodyExpression
    ) {
        String lambdaBodyOpenerLine = lambdaBodyOpenerLine(parameters, bodyExpression);
        if (lambdaBodyOpenerLine.isEmpty()) {
            return "";
        }
        return prefix + "(" + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ") + lambdaBodyOpenerLine;
    }

    record Plan(
        LambdaExpr lambdaExpr,
        Optional<LambdaExpr> nestedLambda,
        Expression bodyExpression,
        String parameters,
        String firstLine,
        boolean bodyFirstSourceLineFits,
        String lambdaBodyOpenerLine,
        String callBodyOpenerLine
    ) {
        boolean firstLineFits(ToIntFunction<String> firstLineWidth, int lineWidth) {
            return firstLineWidth.applyAsInt(firstLine) <= lineWidth;
        }

        boolean bodyOpenerFitsOnContinuation(ToIntFunction<String> bodyOpenerWidth, int lineWidth) {
            return !lambdaBodyOpenerLine.isEmpty() && bodyOpenerWidth.applyAsInt(lambdaBodyOpenerLine) <= lineWidth;
        }

        boolean bodyOpenerOverflows(ToIntFunction<String> bodyOpenerWidth, int lineWidth) {
            return !callBodyOpenerLine.isEmpty() && bodyOpenerWidth.applyAsInt(callBodyOpenerLine) > lineWidth;
        }
    }

    /**
     * The cross-printer boundary for {@link #plan}: the shape the call and chain printers hold so they can build a
     * {@link Plan} at the true continuation column.
     *
     * <p>D1g (#190) widened this from the earlier {@code BiFunction<String, NodeList<Expression>, Optional<Plan>>} to
     * carry the caller's {@link LayoutContext}. The context is threaded-but-not-consulted today (byte-identical); it
     * exists so the lambda-hug admission gate can measure at the same rendered column its callers already use for their
     * Plan-consuming first-line predicates once C10 activates.
     */
    @FunctionalInterface
    interface PlanFactory {
        Optional<Plan> plan(String prefix, NodeList<Expression> arguments, LayoutContext layout);
    }

    /**
     * The cross-printer boundary for {@link #huggableMethodCallArguments}: the shape the call and chain printers hold so
     * they can render a hugged expression-lambda argument list at the true segment column.
     *
     * <p>D3 keystone: this widens the earlier {@code BiFunction<String, NodeList<Expression>, Optional<Doc>>} to carry
     * {@code columnWidth}, the true-segment-column oracle the chain-segment call-site threads. It is
     * threaded-but-NOT-consulted today (byte-identical); the hug body-shape probes still measure at the fixed budget (see
     * {@link #huggableMethodCallArguments(String, NodeList, ToIntFunction)}). Consuming it is the atomic D3 flip.
     */
    @FunctionalInterface
    interface HuggableExpressionLambdaArguments {
        Optional<Doc> render(String prefix, NodeList<Expression> arguments, ToIntFunction<String> columnWidth);
    }

    /**
     * The cross-printer boundary for {@link #methodCallBodyWithOpener}: the shape the chain printer holds so a fanned
     * chain selector can hug a single-method-call-body lambda opener at the true segment column.
     *
     * <p>D3 keystone: widens the earlier {@code BiFunction<String, MethodCallExpr, Optional<Doc>>} to carry
     * {@code columnWidth}. Threaded-but-NOT-consulted today (byte-identical); the opener-fit probe still measures at the
     * fixed budget (see {@link #methodCallBodyWithOpener(String, MethodCallExpr, ToIntFunction)}). Consuming it is the D3 flip.
     */
    @FunctionalInterface
    interface ExpressionLambdaMethodCallBodyOpener {
        Optional<Doc> render(String parameters, MethodCallExpr methodCall, ToIntFunction<String> columnWidth);
    }

    /**
     * The cross-printer boundary for {@link #logicalBinaryLambdaBodyOpenerHug}: the shape the chain printer holds so a
     * fanned chain selector can hug a logical-binary-body lambda opener at the true segment column.
     *
     * <p>D3 keystone: widens the earlier {@code BiFunction<String, MethodCallExpr, Optional<Doc>>} to carry
     * {@code columnWidth}. Threaded-but-NOT-consulted today (byte-identical); the first-operand-fits guard still measures
     * at the fixed budget (see {@link #logicalBinaryLambdaBodyOpenerHug(String, MethodCallExpr, ToIntFunction)}).
     * Consuming it is the D3 flip.
     */
    @FunctionalInterface
    interface ExpressionLambdaLogicalBinaryBodyOpenerHug {
        Optional<Doc> render(String prefix, MethodCallExpr expression, ToIntFunction<String> columnWidth);
    }
}
