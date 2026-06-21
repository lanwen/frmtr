package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders lambda expressions and the lambda-specific argument shapes used by calls and object creation.
 *
 * <p>This helper owns the lambda decision tree: parameter parentheses, commented parameter reconstruction, expression
 * versus block bodies, broken logical bodies, parenthesized lambdas, and lambda arguments that can be hugged by a method
 * call or constructor call. The boundary exists because lambdas are selected by normal expression dispatch, but their
 * argument forms also affect method-call and object-creation layout.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, enclosed-expression suffix decisions, raw source
 * helpers, range predicates, and binary-expression policy. {@link ObjectCreationPrinter} owns constructor layout, and
 * {@link MethodCallPrinter} still owns call and chain layout. This helper receives those decisions as callbacks and
 * only chooses the lambda-specific structure. Representative coverage lives in the block-lambda, method-chain, and
 * variable-declaration formatter fixtures.
 */
final class LambdaExpressionPrinter {

    private final CommentTracker comments;

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final JavaFormatRule<Statement> statementRenderer;

    private final JavaFormatRule<BlockStmt> blockRenderer;

    private final JavaFormatRule<BlockStmt> methodChainLambdaBlockRenderer;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactWithoutOwnComment;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> blockStatementWidth;

    private final BiPredicate<Comment, Node> startsBefore;

    private final BiPredicate<Comment, Node> startsOnSameLine;

    private final ExpressionLambdaArgumentLayout expressionLambdaArguments;

    private final LambdaParameterHeaderLayout lambdaParameterHeaders;

    private final LambdaBodyHeaderLayout lambdaBodyHeaders;

    LambdaExpressionPrinter(
            CommentTracker comments,
            RawSource rawSource,
            SourceShapePolicy sourceShapePolicy,
            SourceText sourceText,
            ObjectCreationLayoutPolicy objectCreationLayoutPolicy,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            JavaFormatRule<Expression> expressionRenderer,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            JavaFormatRule<BlockStmt> methodChainLambdaBlockRenderer,
            BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth,
            BiPredicate<Comment, Node> startsBefore,
            BiPredicate<Comment, Node> startsOnSameLine
    ) {
        this.comments = comments;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.expressionRenderer = expressionRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.statementRenderer = statementRenderer;
        this.blockRenderer = blockRenderer;
        this.methodChainLambdaBlockRenderer = methodChainLambdaBlockRenderer;
        this.binaryExpressionNestedLinesRenderer = binaryExpressionNestedLinesRenderer;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.compactJoin = compactJoin;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.startsBefore = startsBefore;
        this.startsOnSameLine = startsOnSameLine;
        this.lambdaParameterHeaders = new LambdaParameterHeaderLayout(
            rawSource,
            options,
            compact,
            compactJoin,
            currentIndentedWidth
        );
        this.expressionLambdaArguments = new ExpressionLambdaArgumentLayout(
            sourceShapePolicy,
            rawSource,
            sourceText,
            options,
            expressionRenderer,
            brokenMethodCallRenderer,
            brokenObjectCreationRenderer,
            packedMethodCallChainBodyRenderer,
            statementRenderer,
            methodCallArgumentList,
            compact,
            compactJoin,
            binaryExpressionNestedLinesRenderer,
            this::lambdaParameters,
            this::lambdaParametersShouldBreak,
            blockStatementWidth,
            layoutWidth
        );
        this.lambdaBodyHeaders = new LambdaBodyHeaderLayout(
            sourceShapePolicy,
            rawSource,
            options,
            expressionRenderer,
            lambdaParameterHeaders::haveComments,
            this::lambdaParametersShouldBreak,
            lambdaParameterHeaders::forHeader,
            currentIndentedWidth
        );
    }

    Doc parenthesizedLambdaBreak(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        return Doc.concat(
            Doc.text("(" + parameters + " ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, lambdaExpressionBody(expression))),
            Doc.text(")")
        );
    }

    private Doc lambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(expressionRenderer::format)
                .orElseGet(() -> statementRenderer.format(expression.getBody()));
    }

    Doc brokenExpressionLambda(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        return Doc.concat(
            lambdaParameterHeaders.forHeader(expression, parameters),
            Doc.text(" ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, brokenLambdaExpressionBody(expression)))
        );
    }

    Doc lambdaExpression(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        if (expression.getBody().isBlockStmt()) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                blockRenderer.format(expression.getBody().asBlockStmt())
            );
        }
        boolean parametersHaveComments = lambdaParameterHeaders.haveComments(expression);
        if (parametersHaveComments) {
            Optional<String> inlineCommentedLambda = lambdaParameterHeaders.inlineCommentedLambda(expression);
            if (
                inlineCommentedLambda.filter(lambda -> currentIndentedWidth.applyAsInt(lambda) <= options.lineWidth()).isPresent()
            ) {
                return Doc.text(inlineCommentedLambda.orElseThrow());
            }
        }
        Optional<Expression> expressionBody = expression.getExpressionBody();
        String flat = parameters
            + " -> "
            + expressionBody.map(compact).orElseGet(() -> compact.apply(expression.getBody()));
        if (
            !parametersHaveComments
            && expressionBody.filter(expressionLambdaArguments::sourceMultilineLogicalBody).isEmpty()
            && expressionBody.filter(expressionLambdaArguments::sourceMultilineMethodCallBody).isEmpty()
            && expressionBody.filter(expressionLambdaArguments::sourceMultilineBinaryMethodCallBody).isEmpty()
            && expressionBody.filter(body -> lambdaBodyStartsAfterHeader(expression, body))
                    .filter(this::sourceMultilineBodyMustStayBroken)
                    .isEmpty()
            && !lambdaFlatOverflowsInBrokenArgumentList(flat)
            && expressionBody.filter(this::methodCallBodyOverflowsInBrokenArgumentList).isEmpty()
            && currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()
        ) {
            return Doc.text(flat);
        }
        if (parametersHaveComments && expression.getExpressionBody().isPresent()) {
            Expression body = expression.getExpressionBody().orElseThrow();
            if (currentIndentedWidth.applyAsInt(") -> " + compact.apply(body)) <= options.lineWidth()) {
                return Doc.concat(
                    lambdaParameterHeaders.forHeader(expression, parameters),
                    Doc.text(" -> "),
                    expressionRenderer.format(body)
                );
            }
        }
        if (
            lambdaParametersShouldBreak(expression, parameters)
            && expression.getExpressionBody().filter(this::shouldHugBrokenLambdaBody).isPresent()
        ) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                expressionRenderer.format(expression.getExpressionBody().orElseThrow())
            );
        }
        Optional<Doc> methodCallBodyWithOpener = expressionBody.filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .flatMap(methodCall -> expressionLambdaArguments.methodCallBodyWithOpener(parameters, methodCall));
        if (methodCallBodyWithOpener.isPresent()) {
            return methodCallBodyWithOpener.orElseThrow();
        }
        Optional<Doc> methodCallBodyWithHeader = parametersHaveComments
            ? Optional.empty()
            : expressionBody.filter(MethodCallExpr.class::isInstance)
                    .map(MethodCallExpr.class::cast)
                    .flatMap(methodCall -> expressionLambdaArguments.methodCallBodyWithHeader(parameters, methodCall));
        if (methodCallBodyWithHeader.isPresent()) {
            return methodCallBodyWithHeader.orElseThrow();
        }
        Optional<Doc> sourceMultilineMethodCallBody = expressionBody.flatMap(
            body -> lambdaBodyHeaders.sourceMultilineMethodCallBodyWithHeader(expression, parameters, body)
        );
        if (sourceMultilineMethodCallBody.isPresent()) {
            return sourceMultilineMethodCallBody.orElseThrow();
        }
        Optional<Doc> binaryMethodCallBodyWithOpener = expressionBody.filter(BinaryExpr.class::isInstance)
                .map(BinaryExpr.class::cast)
                .flatMap(binary -> expressionLambdaArguments.binaryMethodCallBodyWithOpener(parameters, binary));
        if (binaryMethodCallBodyWithOpener.isPresent()) {
            return binaryMethodCallBodyWithOpener.orElseThrow();
        }
        Optional<Doc> binaryBodyWithOpener = expressionBody.flatMap(
            body -> binaryBodyWithFirstOperandOnHeader(
                expression,
                parameters,
                body
            )
        );
        if (binaryBodyWithOpener.isPresent()) {
            return binaryBodyWithOpener.orElseThrow();
        }
        Optional<Doc> objectCreationBodyWithOpener = expressionBody.filter(ObjectCreationExpr.class::isInstance)
                .map(ObjectCreationExpr.class::cast)
                .flatMap(objectCreation -> objectCreationBodyWithOpener(expression, parameters, objectCreation));
        if (objectCreationBodyWithOpener.isPresent()) {
            return objectCreationBodyWithOpener.orElseThrow();
        }
        Doc body = brokenLambdaExpressionBody(expression);
        return Doc.concat(
            lambdaParameterHeaders.forHeader(expression, parameters),
            Doc.text(" ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, body))
        );
    }

    private Optional<Doc> objectCreationBodyWithOpener(
            LambdaExpr lambda,
            String parameters,
            ObjectCreationExpr objectCreation
    ) {
        if (
            objectCreation.getArguments().isEmpty()
            || (!lambdaBodyStartsAfterHeader(lambda, objectCreation)
                && objectCreationLambdaBodyFits(parameters, objectCreation))
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                lambdaParameterHeaders.forHeader(lambda, parameters),
                Doc.text(" -> "),
                brokenObjectCreationRenderer.apply(objectCreation)
            )
        );
    }

    private boolean objectCreationLambdaBodyFits(String parameters, ObjectCreationExpr objectCreation) {
        String flat = parameters + " -> " + compact.apply(objectCreation);
        return currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()
            && !lambdaFlatOverflowsInBrokenArgumentList(flat);
    }

    private Optional<Doc> binaryBodyWithFirstOperandOnHeader(
            LambdaExpr lambda,
            String parameters,
            Expression body
    ) {
        if (lambdaParameterHeaders.haveComments(lambda) || lambdaParametersShouldBreak(lambda, parameters)) {
            return Optional.empty();
        }
        Optional<Doc> binaryBody = binaryBodyDoc(body);
        if (binaryBody.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> firstOperand = binaryBodyFirstOperandLine(body);
        if (
            firstOperand.filter(
                        operand -> currentIndentedWidth.applyAsInt(parameters + " -> " + operand) <= options.lineWidth()
                    )
                    .isEmpty()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                lambdaParameterHeaders.forHeader(lambda, parameters),
                Doc.text(" -> "),
                Doc.indent(binaryBody.orElseThrow())
            )
        );
    }

    private boolean lambdaBodyStartsAfterHeader(LambdaExpr lambda, Expression body) {
        return lambda.getRange()
                .flatMap(lambdaRange -> body.getRange().map(bodyRange -> bodyRange.begin.line > lambdaRange.begin.line))
                .orElse(false);
    }

    private boolean sourceMultilineBodyMustStayBroken(Expression body) {
        return !(body instanceof BinaryExpr binaryExpr && !isLogicalBinaryOperator(binaryExpr));
    }

    private boolean methodCallBodyOverflowsInBrokenArgumentList(Expression body) {
        if (!(body instanceof MethodCallExpr methodCall)) {
            return false;
        }
        int nestedBodyWidth = blockStatementWidth.applyAsInt(
            options.indentUnit().repeat(3) + compact.apply(methodCall)
        );
        return nestedBodyWidth > options.lineWidth();
    }

    private boolean lambdaFlatOverflowsInBrokenArgumentList(String flat) {
        return blockStatementWidth.applyAsInt(options.indentUnit().repeat(3) + flat) > options.lineWidth();
    }

    private boolean shouldHugBrokenLambdaBody(Expression body) {
        return body instanceof MethodCallExpr methodCall
            && methodCall.getArguments().isEmpty()
            && currentIndentedWidth.applyAsInt(") -> " + compact.apply(methodCall)) <= options.lineWidth();
    }

    /**
     * Breaks expression bodies, forcing logical and over-wide binary bodies into the binary-line renderer.
     *
     * <p>A logical body such as {@code a && b} reads as one condition tree, and a wide relational body can hide an
     * overflowing method-call operand behind the lambda arrow. If the lambda body is already broken, the binary renderer
     * keeps that tree aligned instead of letting the expression dispatcher choose a flat fallback.
     */
    private Doc brokenLambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(body -> binaryBodyDoc(body).orElseGet(() -> brokenNonBinaryLambdaBody(body)))
                .orElseGet(() -> statementRenderer.format(expression.getBody()));
    }

    private Doc brokenNonBinaryLambdaBody(Expression body) {
        if (
            body instanceof ObjectCreationExpr objectCreation
            && currentIndentedWidth.applyAsInt(compact.apply(objectCreation)) > options.lineWidth()
        ) {
            return brokenObjectCreationRenderer.apply(objectCreation);
        }
        if (
            body instanceof MethodCallExpr methodCall
            && currentIndentedWidth.applyAsInt(compact.apply(methodCall)) > options.lineWidth()
        ) {
            return brokenMethodCallRenderer.apply(methodCall);
        }
        return expressionRenderer.format(body);
    }

    private boolean isLogicalBinaryOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR;
    }

    private Optional<Doc> binaryBodyDoc(Expression body) {
        return binaryBody(body)
                .filter(binary -> isLogicalBinaryOperator(binary)
                        || lambdaBodyOverflowsInBrokenArgumentList(body)
                )
                .map(binary -> {
                    Doc lines = binaryExpressionNestedLinesRenderer.apply(binary, true);
                    for (int i = 0; i < enclosedDepth(body); i++) {
                        lines = Doc.concat(Doc.text("("), lines, Doc.text(")"));
                    }
                    return lines;
                });
    }

    private boolean lambdaBodyOverflowsInBrokenArgumentList(Expression body) {
        String flat = compact.apply(body);
        return currentIndentedWidth.applyAsInt(flat) > options.lineWidth()
            || blockStatementWidth.applyAsInt(options.indentUnit().repeat(3) + flat) > options.lineWidth();
    }

    private Optional<BinaryExpr> binaryBody(Expression body) {
        if (body instanceof BinaryExpr binaryExpr) {
            return Optional.of(binaryExpr);
        }
        if (body instanceof EnclosedExpr enclosedExpr) {
            return binaryBody(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    private Optional<String> binaryBodyFirstOperandLine(Expression body) {
        if (body instanceof EnclosedExpr enclosedExpr) {
            return binaryBodyFirstOperandLine(enclosedExpr.getInner()).map(line -> "(" + line);
        }
        if (body instanceof BinaryExpr binaryExpr) {
            return Optional.of(compact.apply(firstBinaryOperand(binaryExpr)));
        }
        return Optional.empty();
    }

    private Expression firstBinaryOperand(BinaryExpr binaryExpr) {
        Expression left = binaryExpr.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == binaryExpr.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
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

    boolean lambdaParametersShouldBreak(LambdaExpr expression, String flatParameters) {
        return lambdaParameterHeaders.shouldBreak(expression, flatParameters);
    }

    String lambdaParameters(LambdaExpr expression) {
        return lambdaParameterHeaders.parameters(expression);
    }

    /**
     * Hugs a single block-body lambda argument when it is at the start or end of the argument list.
     *
     * <p>Those edge positions let the call keep the ordinary argument prefix or suffix without hiding another argument
     * after the lambda body. A block lambda in the middle would make the remaining arguments read like part of the
     * lambda block, so the normal call formatter handles that case.
     */
    Optional<Doc> huggableBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments(
            prefix,
            arguments,
            blockStatementWidth,
            this::lambdaExpression,
            blockRenderer
        );
    }

    Optional<Doc> huggableMethodChainBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments(
            prefix,
            arguments,
            blockStatementWidth,
            this::methodChainLambdaExpression,
            methodChainLambdaBlockRenderer
        );
    }

    /**
     * Hugs a block-lambda argument after the caller supplies the width check for the first rendered line.
     *
     * <p>Statement, method-call, and object-creation contexts use normal block-statement width. Field declarations include
     * the declaration prefix before the call, so they provide their own width probe while sharing the same eligibility and
     * rendering rules.
     */
    Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth
    ) {
        return huggableBlockLambdaArguments(prefix, arguments, firstLineWidth, this::lambdaExpression, blockRenderer);
    }

    private Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth,
            Function<LambdaExpr, Doc> lambdaRenderer,
            JavaFormatRule<BlockStmt> lambdaBlockRenderer
    ) {
        Optional<HuggableBlockLambdaArgument> huggable = huggableBlockLambdaArgument(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        HuggableBlockLambdaArgument argument = huggable.orElseThrow();
        Optional<Doc> sourceMultilineParameters =
            SourceMultilineLambdaCallLayout.blockLambdaArgumentWithSourceMultilineParameters(
                prefix,
                arguments,
                argument.lambdaIndex(),
                argument.lambdaExpr(),
                argument.leadingArguments(),
                compactJoin,
                lambdaParameterHeaders,
                lambdaBlockRenderer
            );
        if (sourceMultilineParameters.isPresent()) {
            return sourceMultilineParameters;
        }
        if (firstLineWidth.applyAsInt(argument.firstLine()) > options.lineWidth()) {
            return Optional.empty();
        }
        String trailingArguments = compactJoin.apply(arguments.subList(argument.lambdaIndex() + 1, arguments.size()));
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "(" + (argument.leadingArguments().isEmpty() ? "" : argument.leadingArguments() + ", ")),
                lambdaRenderer.apply(argument.lambdaExpr()),
                Doc.text((trailingArguments.isEmpty() ? "" : ", " + trailingArguments) + ")")
            )
        );
    }

    private Doc methodChainLambdaExpression(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        if (expression.getBody().isBlockStmt()) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                methodChainLambdaBlockRenderer.format(expression.getBody().asBlockStmt())
            );
        }
        return lambdaExpression(expression);
    }

    /**
     * Returns the exact first line used by the huggable block-lambda argument layout before width is considered.
     */
    Optional<String> huggableBlockLambdaFirstLine(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArgument(prefix, arguments).map(HuggableBlockLambdaArgument::firstLine);
    }

    /**
     * Applies the shared block-lambda argument eligibility rules for both rendering and external first-line probing.
     */
    private Optional<HuggableBlockLambdaArgument> huggableBlockLambdaArgument(
            String prefix,
            NodeList<Expression> arguments
    ) {
        int lambdaIndex = SourceMultilineLambdaCallLayout.blockLambdaArgumentIndex(arguments);
        if (lambdaIndex < 0 || (lambdaIndex > 0 && lambdaIndex < arguments.size() - 1)) {
            return Optional.empty();
        }
        if (SourceMultilineLambdaCallLayout.hasOtherLambdaArgument(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        if (nonLambdaArgumentHasConstructorChainRootNeedingBreak(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = (LambdaExpr) arguments.get(lambdaIndex);
        String parameters = lambdaParameters(lambdaExpr);
        if (lambdaParametersShouldBreak(lambdaExpr, parameters)) {
            return Optional.empty();
        }
        String leadingArguments = compactJoin.apply(arguments.subList(0, lambdaIndex));
        String firstLine = prefix
            + "("
            + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
            + parameters
            + " -> {";
        return Optional.of(new HuggableBlockLambdaArgument(lambdaIndex, lambdaExpr, leadingArguments, firstLine));
    }

    private boolean nonLambdaArgumentHasConstructorChainRootNeedingBreak(
            NodeList<Expression> arguments,
            int lambdaIndex
    ) {
        for (int index = 0; index < arguments.size(); index++) {
            if (index != lambdaIndex && expressionHasConstructorChainRootNeedingBreak(arguments.get(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean expressionHasConstructorChainRootNeedingBreak(Expression expression) {
        return expression.findAll(MethodCallExpr.class)
                .stream()
                .anyMatch(this::methodCallRootConstructorNeedsBreak);
    }

    private boolean methodCallRootConstructorNeedsBreak(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        if (calls.isEmpty() || !(root instanceof ObjectCreationExpr objectCreation)) {
            return false;
        }
        int compactRootWidth = currentIndentedWidth.applyAsInt(compact.apply(objectCreation));
        boolean compactRootCanStay = objectCreationLayoutPolicy.canKeepCompactChainRoot(
            objectCreation,
            compactRootWidth,
            options.lineWidth()
        );
        return !compactRootCanStay;
    }

    private Expression methodCallChainRoot(MethodCallExpr expression, List<MethodCallExpr> calls) {
        if (expression.getScope().orElse(null) instanceof MethodCallExpr methodCallExpr) {
            Expression root = methodCallChainRoot(methodCallExpr, calls);
            calls.add(expression);
            return root;
        }
        if (expression.getScope().isEmpty()) {
            return expression;
        }
        calls.add(expression);
        return expression.getScope().orElseThrow();
    }

    private record HuggableBlockLambdaArgument(
        int lambdaIndex,
        LambdaExpr lambdaExpr,
        String leadingArguments,
        String firstLine
    ) {}

    /**
     * Rebuilds a single expression-lambda argument when comments sit around the lambda boundary.
     *
     * <p>JavaParser can attach those comments to the call, the method name, or the lambda itself. This method collects
     * only line and block comments around the single lambda argument, then prints leading comments before the lambda and
     * trailing comments after it inside the broken call argument list. Comments after the completed call stay out of this
     * path so chain renderers can keep them after the call's closing parenthesis.
     */
    Optional<Doc> commentedExpressionLambdaArgument(String prefix, MethodCallExpr expression) {
        if (
            expression.getArguments().size() != 1
            || !(expression.getArgument(0) instanceof LambdaExpr lambdaExpr)
            || lambdaExpr.getExpressionBody().isEmpty()
        ) {
            return Optional.empty();
        }
        List<Comment> commentsAroundLambda = new ArrayList<>();
        expression.getOrphanComments()
                .stream()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .forEach(commentsAroundLambda::add);
        lambdaExpr.getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .ifPresent(commentsAroundLambda::add);
        expression.getName()
                .getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .filter(comment -> startsBefore.test(comment, lambdaExpr))
                .ifPresent(commentsAroundLambda::add);
        if (commentsAroundLambda.isEmpty()) {
            return Optional.empty();
        }
        commentsAroundLambda.sort(CommentIndex.sourceOrderComparator());
        Optional<Doc> inlineBlockComment = inlineBlockCommentedExpressionLambdaArgument(
            prefix,
            lambdaExpr,
            commentsAroundLambda
        );
        if (inlineBlockComment.isPresent()) {
            return inlineBlockComment;
        }
        Optional<Doc> brokenLeadingBlockComment = brokenLeadingBlockCommentedExpressionLambdaArgument(
            prefix,
            lambdaExpr,
            commentsAroundLambda
        );
        if (brokenLeadingBlockComment.isPresent()) {
            return brokenLeadingBlockComment;
        }
        List<Doc> leading = commentsAroundLambda.stream()
                .filter(comment -> isLeadingExpressionLambdaComment(lambdaExpr, comment))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        List<Doc> trailing = commentsAroundLambda.stream()
                .filter(comment -> !isLeadingExpressionLambdaComment(lambdaExpr, comment))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        List<Doc> argumentLines = new ArrayList<>();
        argumentLines.addAll(leading);
        argumentLines.add(lambdaExpression(lambdaExpr));
        argumentLines.addAll(trailing);
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentLines))),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private boolean isLineOrBlockComment(Comment comment) {
        return comment instanceof LineComment || comment instanceof BlockComment;
    }

    private boolean trailsCompletedCall(MethodCallExpr expression, Comment comment) {
        return CommentIndex.startsAfterNodeOnSameLine(expression, comment);
    }

    /**
     * Keeps a leading block comment and expression lambda on one line when the whole call still fits.
     *
     * <p>This is only valid for a same-line leading block comment, because the comment visually belongs to the lambda
     * argument. Line comments and trailing comments force the broken form so their line ownership stays clear.
     */
    private Optional<Doc> inlineBlockCommentedExpressionLambdaArgument(
            String prefix,
            LambdaExpr lambdaExpr,
            List<Comment> commentsAroundLambda
    ) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (
            !(comment instanceof BlockComment)
            || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
            || !startsOnSameLine.test(comment, lambdaExpr)
        ) {
            return Optional.empty();
        }
        String call = prefix
            + "("
            + comment.toString().stripTrailing()
            + " "
            + compactWithoutOwnComment.apply(lambdaExpr)
            + ")";
        if (currentIndentedWidth.applyAsInt(call) > options.lineWidth()) {
            return Optional.empty();
        }
        comments.comment(comment);
        return Optional.of(Doc.text(call));
    }

    /**
     * Breaks a same-line leading block comment before the expression lambda when the compact call is too wide.
     */
    private Optional<Doc> brokenLeadingBlockCommentedExpressionLambdaArgument(
            String prefix,
            LambdaExpr lambdaExpr,
            List<Comment> commentsAroundLambda
    ) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (
            !(comment instanceof BlockComment)
            || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
            || !startsOnSameLine.test(comment, lambdaExpr)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        comments.comment(comment),
                        Doc.text(" "),
                        lambdaExpression(lambdaExpr)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private boolean isLeadingExpressionLambdaComment(LambdaExpr lambdaExpr, Comment comment) {
        return lambdaExpr.getComment().filter(ownComment -> ownComment == comment).isPresent()
            || startsBefore.test(comment, lambdaExpr);
    }

    /**
     * Hugs expression-body lambdas whose body naturally wants to start on the next line of a call argument.
     *
     * <p>Method calls with arguments, conditional expressions, and nested expression lambdas have a useful first line
     * ending at {@code ->}. Once the flat call is too wide but that first line still fits, the body can break underneath
     * the lambda header without switching the whole method call to the generic argument-list shape.
     */
    Optional<Doc> huggableMethodCallExpressionLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return expressionLambdaArguments.huggableMethodCallArguments(prefix, arguments);
    }

    Optional<ExpressionLambdaArgumentLayout.Plan> huggableExpressionLambdaArgumentPlan(
            String prefix,
            NodeList<Expression> arguments
    ) {
        return expressionLambdaArguments.plan(prefix, arguments);
    }
}
