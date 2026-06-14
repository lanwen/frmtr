package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
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

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer;

    private final JavaFormatRule<Statement> statementRenderer;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak;

    private final ToIntFunction<String> blockStatementWidth;

    ExpressionLambdaArgumentLayout(
        RawSource rawSource,
        FormatterOptions options,
        JavaFormatRule<Expression> expressionRenderer,
        Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
        Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
        BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer,
        JavaFormatRule<Statement> statementRenderer,
        BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
        Function<Node, String> compact,
        Function<List<? extends Node>, String> compactJoin,
        BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer,
        Function<LambdaExpr, String> lambdaParameters,
        BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
        ToIntFunction<String> blockStatementWidth
    ) {
        this.rawSource = rawSource;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.packedMethodCallChainBodyRenderer = packedMethodCallChainBodyRenderer;
        this.statementRenderer = statementRenderer;
        this.methodCallArgumentList = methodCallArgumentList;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.binaryExpressionNestedLinesRenderer = binaryExpressionNestedLinesRenderer;
        this.lambdaParameters = lambdaParameters;
        this.lambdaParametersShouldBreak = lambdaParametersShouldBreak;
        this.blockStatementWidth = blockStatementWidth;
    }

    /**
     * Renders a lambda expression body as {@code parameters -> methodCall(} when that opener fits by itself.
     */
    Optional<Doc> methodCallBodyWithOpener(String parameters, MethodCallExpr methodCall) {
        if (
            methodCall.getArguments().isEmpty()
            || methodCall.getScope().filter(scope -> rawSource.rawWithoutOwnComment(scope).contains("\n")).isPresent()
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
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
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
                        expressionRenderer.format(right)
                    )
                )
            )
        );
    }

    /**
     * Renders a method-call argument list containing a single eligible expression lambda.
     */
    Optional<Doc> huggableMethodCallArguments(String prefix, NodeList<Expression> arguments) {
        Optional<Plan> huggable = plan(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        Plan argument = huggable.orElseThrow();
        LambdaExpr lambdaExpr = argument.lambdaExpr();
        Optional<LambdaExpr> nestedLambda = argument.nestedLambda();
        String firstLine = argument.firstLine();
        Expression bodyExpression = argument.bodyExpression();
        if (nestedLambda.isPresent()) {
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
        if (logicalBinaryBody(bodyExpression).isPresent()) {
            if (logicalBinaryFirstLineFits(firstLine, bodyExpression)) {
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
        Optional<PackedLambdaBody> packedBody = packedLambdaBody(firstLine, bodyExpression);
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
            && methodCallBodyWithOpener(argument.parameters(), methodCall).isPresent()
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
     * Builds the shared expression-lambda argument plan used by call and chain printers for width decisions.
     */
    Optional<Plan> plan(String prefix, NodeList<Expression> arguments) {
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
        String firstLine =
            prefix
            + "("
            + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
            + lambdaFirstLine;
        String flat = prefix + "(" + compactJoin.apply(arguments) + ")";
        boolean sourceMultilineBody =
            body.filter(this::sourceMultilineLogicalBody).isPresent()
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
            && methodCallBodyWithOpener(parameters, methodCall).isPresent()
            && packedBodyCallWithoutClosingLine(firstLine, bodyExpression).isEmpty()
            && packedBodyCallScopeWithoutClosingLine(firstLine, bodyExpression).isEmpty()
            && !bodyFirstSourceLineFits(firstLine, bodyExpression)
        ) {
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    boolean sourceMultilineLogicalBody(Expression body) {
        return logicalBinaryBody(body).isPresent() && rawSource.rawWithoutOwnComment(body).contains("\n");
    }

    boolean sourceMultilineMethodCallBody(Expression body) {
        return (
            body instanceof MethodCallExpr methodCall
            && (rawSource.rawWithoutOwnComment(methodCall).contains("\n") || methodCall.getScope()
                .filter(scope -> rawSource.rawWithoutOwnComment(scope).contains("\n"))
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
            && (sourceMultilineMethodCallBody(methodCall) || bodyFirstSourceLineOverflows(firstLine, methodCall) || bodyCompactLineOverflows(
                firstLine,
                methodCall
            ))
        ) {
            return brokenMethodCallRenderer.apply(methodCall);
        }
        if (
            bodyExpression instanceof ObjectCreationExpr objectCreation
            && expressionFirstLineWidth(firstLine + " " + compact.apply(objectCreation)) > options.lineWidth()
        ) {
            return brokenObjectCreationRenderer.apply(objectCreation);
        }
        return expressionRenderer.format(bodyExpression);
    }

    private int brokenArgumentListLambdaBodyWidth(String bodyLine) {
        return blockStatementWidth.applyAsInt(options.indentUnit().repeat(3) + bodyLine);
    }

    /**
     * Picks the first body shape that can share the lambda opener and records who owns the closing call suffix.
     */
    private Optional<PackedLambdaBody> packedLambdaBody(String firstLine, Expression bodyExpression) {
        return packedObjectCreationWithoutClosingLine(firstLine, bodyExpression)
            .map(doc -> PackedLambdaBody.closingOnOwnLine(doc, "))"))
            .or(() -> packedBodyCallWithBlockLambda(firstLine, bodyExpression).map(
                    doc -> PackedLambdaBody.attachedClosing(doc, "))")
            ))
            .or(() -> packedConditionalBody(firstLine, bodyExpression).map(
                    doc -> PackedLambdaBody.closingOnOwnLine(doc, ")")
            ))
            .or(() -> packedBodyCallWithoutClosingLine(firstLine, bodyExpression).map(
                    doc -> PackedLambdaBody.closingOnOwnLine(doc, "))")
            ))
            .or(() -> packedMethodCallChainBody(firstLine, bodyExpression).map(
                    doc -> PackedLambdaBody.closingOnOwnLine(doc, ")")
            ))
            .or(() -> packedBodyCallScopeWithoutClosingLine(firstLine, bodyExpression).map(
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
                        expressionRenderer.format(conditionalExpr.getThenExpr()),
                        Doc.HARD_LINE,
                        Doc.text(": "),
                        expressionRenderer.format(conditionalExpr.getElseExpr())
                    )
                )
            )
        );
    }

    private Optional<Doc> packedBodyCallWithoutClosingLine(String firstLine, Expression bodyExpression) {
        if (
            !(bodyExpression instanceof MethodCallExpr methodCall)
            || methodCall.getArguments().isEmpty()
            || methodCall.getScope().filter(scope -> rawSource.rawWithoutOwnComment(scope).contains("\n")).isPresent()
        ) {
            return Optional.empty();
        }
        String opener = methodCallPrefix(methodCall) + "(";
        if (expressionFirstLineWidth(firstLine + " " + opener) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(opener),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)
                    )
                )
            )
        );
    }

    private Optional<Doc> packedBodyCallWithBlockLambda(
        String firstLine,
        Expression bodyExpression
    ) {
        if (
            !(bodyExpression instanceof MethodCallExpr methodCall)
            || methodCall.getScope().filter(scope -> rawSource.rawWithoutOwnComment(scope).contains("\n")).isPresent()
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
            || expressionFirstLineWidth(firstLine + " " + opener + parameters + " -> {") > options.lineWidth()
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
                                    .map(statementRenderer::format)
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

    private Optional<Doc> packedObjectCreationWithoutClosingLine(String firstLine, Expression bodyExpression) {
        if (
            !(bodyExpression instanceof ObjectCreationExpr objectCreation)
            || objectCreation.getArguments().isEmpty()
        ) {
            return Optional.empty();
        }
        String opener = objectCreationPrefix(objectCreation) + "(";
        if (expressionFirstLineWidth(firstLine + " " + opener) > options.lineWidth()) {
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

    private Optional<Doc> packedBodyCallScopeWithoutClosingLine(String firstLine, Expression bodyExpression) {
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
        if (expressionFirstLineWidth(firstLine + " " + scope) > options.lineWidth()) {
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

    private Optional<Doc> logicalBinaryBodyDoc(Expression body) {
        return logicalBinaryBody(body).map(binary -> {
            Doc lines = binaryExpressionNestedLinesRenderer.apply(binary, true);
            for (int i = 0; i < enclosedDepth(body); i++) {
                lines = Doc.concat(Doc.text("("), lines, Doc.text(")"));
            }
            return lines;
        });
    }

    private boolean isLogicalBinaryOperator(BinaryExpr expression) {
        return (
            expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR
        );
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

    private int expressionLineWidth(String line, LambdaExpr lambdaExpr, String lambdaText) {
        int lambdaOffset = line.indexOf(lambdaText);
        if (lambdaOffset < 0) {
            return expressionFirstLineWidth(line);
        }
        return lambdaExpr.getRange()
            .map(range -> Math.max(0, range.begin.column + 1 - lambdaOffset) + line.length())
            .orElseGet(() -> expressionFirstLineWidth(line));
    }

    private boolean bodyFirstSourceLineFits(String firstLine, Expression bodyExpression) {
        return (
            rawSource.rawWithoutOwnComment(bodyExpression).contains("\n")
            && expressionFirstLineWidth(firstLine + " " + bodyFirstSourceLine(bodyExpression)) <= options.lineWidth()
        );
    }

    private boolean logicalBinaryFirstLineFits(String firstLine, Expression bodyExpression) {
        return logicalBinaryFirstLine(bodyExpression)
            .map(bodyFirstLine -> expressionFirstLineWidth(firstLine + " " + bodyFirstLine) <= options.lineWidth())
            .orElse(false);
    }

    private Optional<String> logicalBinaryFirstLine(Expression bodyExpression) {
        if (rawSource.rawWithoutOwnComment(bodyExpression).contains("\n")) {
            return Optional.of(bodyFirstSourceLine(bodyExpression));
        }
        if (bodyExpression instanceof EnclosedExpr enclosedExpr) {
            return logicalBinaryFirstLine(enclosedExpr.getInner()).map(line -> "(" + line);
        }
        return logicalBinaryBody(bodyExpression)
            .map(BinaryExpr::getLeft)
            .map(compact);
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

    private boolean huggableBody(Expression body) {
        if (body instanceof MethodCallExpr methodCall) {
            return !methodCall.getArguments().isEmpty() || sourceMultilineMethodCallBody(methodCall);
        }
        if (body instanceof ObjectCreationExpr objectCreation) {
            return !objectCreation.getArguments().isEmpty();
        }
        if (body instanceof ConditionalExpr) {
            return true;
        }
        if (logicalBinaryBody(body).isPresent()) {
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
        return (
            body instanceof MethodCallExpr methodCall
            && methodCall.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
            && (bodyFirstSourceLineOverflows(firstLine, methodCall) || bodyCompactLineOverflows(firstLine, methodCall))
        );
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
        return (
            expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
            + expression.getTypeArguments().map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">").orElse("")
            + expression.getNameAsString()
        );
    }

    private boolean sourceMultilineBinaryMethodCallBody(Expression body) {
        return binaryMethodCallLeftOperand(body)
            .filter(methodCall -> rawSource.rawWithoutOwnComment(methodCall).contains("\n"))
            .isPresent();
    }

    private Optional<MethodCallExpr> binaryMethodCallLeftOperand(Expression body) {
        if (
            !(body instanceof BinaryExpr binaryExpr)
            || !(binaryExpr.getLeft() instanceof MethodCallExpr methodCall)
            || !binaryExpr.getAllContainedComments().isEmpty()
            || methodCall.getArguments().isEmpty()
            || methodCall.getScope().filter(scope -> rawSource.rawWithoutOwnComment(scope).contains("\n")).isPresent()
        ) {
            return Optional.empty();
        }
        return Optional.of(methodCall);
    }

    private String methodCallSelector(MethodCallExpr expression) {
        return (
            expression.getTypeArguments().map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">").orElse("")
            + expression.getNameAsString()
        );
    }

    private String objectCreationPrefix(ObjectCreationExpr expression) {
        return (
            expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
            + "new "
            + expression.getTypeArguments().map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">").orElse("")
            + compact.apply(expression.getType())
        );
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
     * Carries a packed lambda body with its call-closing policy so body selection does not hide suffix ownership in
     * repeated ad hoc render branches.
     */
    private record PackedLambdaBody(Doc doc, String closingSuffix, Placement placement) {
        static PackedLambdaBody closingOnOwnLine(Doc doc, String closingSuffix) {
            return new PackedLambdaBody(doc, closingSuffix, Placement.CLOSING_ON_OWN_LINE);
        }

        static PackedLambdaBody attachedClosing(Doc doc, String closingSuffix) {
            return new PackedLambdaBody(doc, closingSuffix, Placement.ATTACHED_CLOSING);
        }

        Doc render(String firstLine) {
            return switch (placement) {
                case CLOSING_ON_OWN_LINE -> Doc.concat(
                    Doc.text(firstLine + " "),
                    Doc.indent(doc),
                    Doc.HARD_LINE,
                    Doc.text(closingSuffix)
                );
                case ATTACHED_CLOSING -> Doc.concat(
                    Doc.text(firstLine + " "),
                    doc,
                    Doc.text(closingSuffix)
                );
            };
        }
    }

    private enum Placement {
        CLOSING_ON_OWN_LINE,
        ATTACHED_CLOSING,
    }
}
