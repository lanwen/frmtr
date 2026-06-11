package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
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
 * only chooses the lambda-specific structure.
 * Representative fixture pairs for this boundary include
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/lambda/arrow-parens-always/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/lambda/arrow-parens-always/frmtr.output.java},
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/lambda/arrow-parens-avoid/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/lambda/arrow-parens-avoid/frmtr.output.java},
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/member_chain/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/member_chain/frmtr.output.java}, and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/variables/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/variables/frmtr.output.java}.
 */
final class LambdaExpressionPrinter {
    private final CommentTracker comments;
    private final RawSource rawSource;
    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;
    private final FormatterOptions options;
    private final JavaFormatRule<Expression> expressionRenderer;
    private final JavaFormatRule<Statement> statementRenderer;
    private final JavaFormatRule<BlockStmt> blockRenderer;
    private final BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer;
    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;
    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;
    private final Function<Node, String> compact;
    private final Function<Node, String> compactWithoutOwnComment;
    private final Function<List<? extends Node>, String> compactJoin;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> blockStatementWidth;
    private final BiPredicate<Comment, Node> startsBefore;
    private final BiPredicate<Comment, Node> startsOnSameLine;

    LambdaExpressionPrinter(
            CommentTracker comments,
            RawSource rawSource,
            ObjectCreationLayoutPolicy objectCreationLayoutPolicy,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth,
            BiPredicate<Comment, Node> startsBefore,
            BiPredicate<Comment, Node> startsOnSameLine) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.statementRenderer = statementRenderer;
        this.blockRenderer = blockRenderer;
        this.binaryExpressionNestedLinesRenderer = binaryExpressionNestedLinesRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.methodCallArgumentList = methodCallArgumentList;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.compactJoin = compactJoin;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.startsBefore = startsBefore;
        this.startsOnSameLine = startsOnSameLine;
    }

    Doc parenthesizedLambdaBreak(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        return Doc.concat(
                Doc.text("(" + parameters + " ->"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, lambdaExpressionBody(expression))),
                Doc.text(")"));
    }

    private Doc lambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(expressionRenderer::format)
                .orElseGet(() -> statementRenderer.format(expression.getBody()));
    }

    Doc lambdaExpression(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        if (expression.getBody().isBlockStmt()) {
            return Doc.concat(lambdaParametersForHeader(expression, parameters), Doc.text(" -> "), blockRenderer.format(expression.getBody().asBlockStmt()));
        }
        boolean parametersHaveComments = lambdaParametersHaveComments(expression);
        if (parametersHaveComments) {
            Optional<String> inlineCommentedLambda = inlineCommentedLambda(expression);
            if (inlineCommentedLambda.filter(lambda -> currentIndentedWidth.applyAsInt(lambda) <= options.lineWidth()).isPresent()) {
                return Doc.text(inlineCommentedLambda.orElseThrow());
            }
        }
        Optional<Expression> expressionBody = expression.getExpressionBody();
        String flat = parameters + " -> " + expressionBody
                .map(compact)
                .orElseGet(() -> compact.apply(expression.getBody()));
        if (!parametersHaveComments
                && expressionBody.filter(this::sourceMultilineLogicalBody).isEmpty()
                && expressionBody.filter(this::sourceMultilineMethodCallBody).isEmpty()
                && currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        if (parametersHaveComments && expression.getExpressionBody().isPresent()) {
            Expression body = expression.getExpressionBody().orElseThrow();
            if (currentIndentedWidth.applyAsInt(") -> " + compact.apply(body)) <= options.lineWidth()) {
                return Doc.concat(lambdaParametersForHeader(expression, parameters), Doc.text(" -> "), expressionRenderer.format(body));
            }
        }
        if (lambdaParametersShouldBreak(expression, parameters)
                && expression.getExpressionBody().filter(this::shouldHugBrokenLambdaBody).isPresent()) {
            return Doc.concat(
                    lambdaParametersForHeader(expression, parameters),
                    Doc.text(" -> "),
                    expressionRenderer.format(expression.getExpressionBody().orElseThrow()));
        }
        Optional<Doc> methodCallBodyWithOpener = expressionBody
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .flatMap(methodCall -> lambdaMethodCallBodyWithOpener(parameters, methodCall));
        if (methodCallBodyWithOpener.isPresent()) {
            return methodCallBodyWithOpener.orElseThrow();
        }
        Doc body = brokenLambdaExpressionBody(expression);
        return Doc.concat(
                lambdaParametersForHeader(expression, parameters),
                Doc.text(" ->"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, body)));
    }

    private boolean shouldHugBrokenLambdaBody(Expression body) {
        return body instanceof MethodCallExpr methodCall
                && methodCall.getArguments().isEmpty()
                && currentIndentedWidth.applyAsInt(") -> " + compact.apply(methodCall)) <= options.lineWidth();
    }

    private Optional<Doc> lambdaMethodCallBodyWithOpener(String parameters, MethodCallExpr methodCall) {
        if (methodCall.getArguments().isEmpty()
                || methodCall.getScope().filter(scope -> rawSource.rawWithoutOwnComment(scope).contains("\n")).isPresent()) {
            return Optional.empty();
        }
        String opener = methodCallPrefix(methodCall) + "(";
        String firstLine = parameters + " -> " + opener;
        if (huggableExpressionFirstLineWidth(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(firstLine),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private Optional<String> inlineCommentedLambda(LambdaExpr expression) {
        if (expression.getComment().isPresent() || expression.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        return lambdaParameterText(expression)
                .filter(parameterText -> parameterText.contains("/*"))
                .filter(parameterText -> !parameterText.contains("//"))
                .flatMap(this::compactInlineCommentedLambdaParameters)
                .map(parameters -> parameters + " -> " + compact.apply(expression.getExpressionBody().orElseThrow()));
    }

    private Optional<String> compactInlineCommentedLambdaParameters(String parameterText) {
        List<String> lines = parameterText.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("/*")) {
                return Optional.empty();
            }
        }
        return Optional.of(rawSource.normalizeWhitespace(String.join(" ", lines))
                .replace("( /*", "(/*")
                .replaceAll(",\\s*", ", ")
                .replaceAll("\\s+\\)", ")"));
    }

    /**
     * Breaks expression bodies, forcing logical binary bodies into the binary-line renderer.
     *
     * <p>A logical body such as {@code a && b} reads as one condition tree. If the lambda body is already broken, the
     * binary renderer keeps that tree aligned instead of letting the expression dispatcher choose a flat fallback.
     */
    private Doc brokenLambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(body -> logicalBinaryBodyDoc(body).orElseGet(() -> expressionRenderer.format(body)))
                .orElseGet(() -> statementRenderer.format(expression.getBody()));
    }

    private boolean isLogicalBinaryOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
                || expression.getOperator() == BinaryExpr.Operator.OR;
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

    private Doc lambdaParametersForHeader(LambdaExpr expression, String flatParameters) {
        if (lambdaParametersHaveComments(expression)) {
            return commentedLambdaParametersForHeader(expression);
        }
        if (!lambdaParametersShouldBreak(expression, flatParameters)) {
            return Doc.text(flatParameters);
        }
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), expression.getParameters().stream()
                                .map(parameter -> Doc.text(compact.apply(parameter)))
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc commentedLambdaParametersForHeader(LambdaExpr expression) {
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.HARD_LINE, commentedLambdaParameterLines(expression).stream()
                                .map(Doc::text)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    /**
     * Reconstructs commented lambda parameters from the raw token text before {@code ->}.
     *
     * <p>JavaParser does not expose comments inside the parameter list as separator-level trivia. The formatter reads
     * the original parameter text, strips the outer parentheses when present, and then splits comma-separated parameters
     * while keeping line and block comments on the line where the source placed them.
     */
    private List<String> commentedLambdaParameterLines(LambdaExpr expression) {
        String parameterText = lambdaParameterText(expression).orElseGet(() -> compactJoin.apply(expression.getParameters()));
        if (parameterText.startsWith("(") && parameterText.endsWith(")")) {
            parameterText = parameterText.substring(1, parameterText.length() - 1);
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : parameterText.lines().map(String::strip).toList()) {
            if (rawLine.isEmpty()) {
                continue;
            }
            addCommentedLambdaParameterLine(lines, rawLine);
        }
        return lines;
    }

    private void addCommentedLambdaParameterLine(List<String> lines, String rawLine) {
        int lineComment = rawLine.indexOf("//");
        if (lineComment >= 0) {
            String beforeComment = rawLine.substring(0, lineComment).stripTrailing();
            String comment = rawLine.substring(lineComment).stripTrailing();
            if (beforeComment.isBlank()) {
                lines.add(comment);
                return;
            }
            addCommaSeparatedLambdaParameters(lines, beforeComment, comment);
            return;
        }
        if (rawLine.startsWith("/*")) {
            lines.add(rawLine);
            return;
        }
        addCommaSeparatedLambdaParameters(lines, rawLine, "");
    }

    private void addCommaSeparatedLambdaParameters(List<String> lines, String text, String trailingComment) {
        boolean lineEndsWithComma = text.stripTrailing().endsWith(",");
        String[] parameters = text.split(",");
        for (int i = 0; i < parameters.length; i++) {
            String parameter = parameters[i].strip();
            if (parameter.isEmpty()) {
                continue;
            }
            boolean last = i == parameters.length - 1;
            if (!last) {
                lines.add(parameter + ",");
            } else if (!trailingComment.isBlank()) {
                lines.add(parameter + (lineEndsWithComma ? ", " : " ") + trailingComment);
            } else {
                lines.add(parameter + (lineEndsWithComma ? "," : ""));
            }
        }
    }

    private boolean lambdaParametersHaveComments(LambdaExpr expression) {
        return lambdaParameterText(expression)
                .map(parameterText -> parameterText.contains("//") || parameterText.contains("/*"))
                .orElseGet(() -> expression.getParameters().stream()
                        .anyMatch(parameter -> !parameter.getAllContainedComments().isEmpty()));
    }

    private Optional<String> lambdaParameterText(LambdaExpr expression) {
        return expression.getTokenRange()
                .map(Object::toString)
                .filter(raw -> raw.contains("->"))
                .map(raw -> raw.substring(0, raw.indexOf("->")).strip());
    }

    boolean lambdaParametersShouldBreak(LambdaExpr expression, String flatParameters) {
        return expression.getParameters().size() > 1
                && currentIndentedWidth.applyAsInt(flatParameters + " -> {}") > options.lineWidth();
    }

    String lambdaParameters(LambdaExpr expression) {
        if (expression.getParameters().size() != 1) {
            return "(" + compactJoin.apply(expression.getParameters()) + ")";
        }
        String parameter = compact.apply(expression.getParameters().get(0));
        if (options.lambdaArrowParens() == FormatterOptions.LambdaArrowParens.ALWAYS) {
            return "(" + parameter + ")";
        }
        if (options.lambdaArrowParens() == FormatterOptions.LambdaArrowParens.AVOID && lambdaParameterCanAvoidParens(expression)) {
            return parameter;
        }
        return expression.isEnclosingParameters() ? "(" + parameter + ")" : parameter;
    }

    private boolean lambdaParameterCanAvoidParens(LambdaExpr expression) {
        return expression.getParameters().size() == 1
                && expression.getParameters().get(0).getAnnotations().isEmpty()
                && expression.getParameters().get(0).getModifiers().isEmpty()
                && expression.getParameters().get(0).getType().isUnknownType();
    }

    /**
     * Hugs a single block-body lambda argument when it is at the start or end of the argument list.
     *
     * <p>Those edge positions let the call keep the ordinary argument prefix or suffix without hiding another argument
     * after the lambda body. A block lambda in the middle would make the remaining arguments read like part of the
     * lambda block, so the normal call formatter handles that case.
     */
    Optional<Doc> huggableBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments(prefix, arguments, blockStatementWidth);
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
            ToIntFunction<String> firstLineWidth) {
        Optional<HuggableBlockLambdaArgument> huggable = huggableBlockLambdaArgument(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        HuggableBlockLambdaArgument argument = huggable.orElseThrow();
        if (firstLineWidth.applyAsInt(argument.firstLine()) > options.lineWidth()) {
            return Optional.empty();
        }
        String trailingArguments = compactJoin.apply(arguments.subList(argument.lambdaIndex() + 1, arguments.size()));
        return Optional.of(Doc.concat(
                Doc.text(prefix + "(" + (argument.leadingArguments().isEmpty() ? "" : argument.leadingArguments() + ", ")),
                lambdaExpression(argument.lambdaExpr()),
                Doc.text((trailingArguments.isEmpty() ? "" : ", " + trailingArguments) + ")")));
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
            NodeList<Expression> arguments) {
        int lambdaIndex = blockLambdaArgumentIndex(arguments);
        if (lambdaIndex < 0 || (lambdaIndex > 0 && lambdaIndex < arguments.size() - 1)) {
            return Optional.empty();
        }
        if (hasOtherLambdaArgument(arguments, lambdaIndex)) {
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
        String firstLine = prefix + "("
                + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
                + parameters + " -> {";
        return Optional.of(new HuggableBlockLambdaArgument(lambdaIndex, lambdaExpr, leadingArguments, firstLine));
    }

    private boolean nonLambdaArgumentHasConstructorChainRootNeedingBreak(
            NodeList<Expression> arguments,
            int lambdaIndex) {
        for (int index = 0; index < arguments.size(); index++) {
            if (index != lambdaIndex && expressionHasConstructorChainRootNeedingBreak(arguments.get(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean expressionHasConstructorChainRootNeedingBreak(Expression expression) {
        return expression.findAll(MethodCallExpr.class).stream()
                .anyMatch(this::methodCallRootConstructorNeedsBreak);
    }

    private boolean methodCallRootConstructorNeedsBreak(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        return !calls.isEmpty()
                && root instanceof ObjectCreationExpr objectCreation
                && !objectCreationLayoutPolicy.canKeepCompactChainRoot(
                        objectCreation,
                        currentIndentedWidth.applyAsInt(compact.apply(objectCreation)),
                        options.lineWidth());
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
            String firstLine) {}

    /**
     * Rebuilds a single expression-lambda argument when comments sit around the lambda boundary.
     *
     * <p>JavaParser can attach those comments to the call, the method name, or the lambda itself. This method collects
     * only line and block comments around the single lambda argument, then prints leading comments before the lambda and
     * trailing comments after it inside the broken call argument list. Comments after the completed call stay out of this
     * path so chain renderers can keep them after the call's closing parenthesis.
     */
    Optional<Doc> commentedExpressionLambdaArgument(String prefix, MethodCallExpr expression) {
        if (expression.getArguments().size() != 1
                || !(expression.getArgument(0) instanceof LambdaExpr lambdaExpr)
                || lambdaExpr.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        List<Comment> commentsAroundLambda = new ArrayList<>();
        expression.getOrphanComments().stream()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .forEach(commentsAroundLambda::add);
        lambdaExpr.getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .ifPresent(commentsAroundLambda::add);
        expression.getName().getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .filter(comment -> startsBefore.test(comment, lambdaExpr))
                .ifPresent(commentsAroundLambda::add);
        if (commentsAroundLambda.isEmpty()) {
            return Optional.empty();
        }
        commentsAroundLambda.sort(CommentIndex.sourceOrderComparator());
        Optional<Doc> inlineBlockComment = inlineBlockCommentedExpressionLambdaArgument(prefix, lambdaExpr, commentsAroundLambda);
        if (inlineBlockComment.isPresent()) {
            return inlineBlockComment;
        }
        Optional<Doc> brokenLeadingBlockComment = brokenLeadingBlockCommentedExpressionLambdaArgument(
                prefix,
                lambdaExpr,
                commentsAroundLambda);
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
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentLines))),
                Doc.HARD_LINE,
                Doc.text(")")));
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
            List<Comment> commentsAroundLambda) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (!(comment instanceof BlockComment) || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
                || !startsOnSameLine.test(comment, lambdaExpr)) {
            return Optional.empty();
        }
        String call = prefix + "(" + comment.toString().stripTrailing() + " " + compactWithoutOwnComment.apply(lambdaExpr) + ")";
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
            List<Comment> commentsAroundLambda) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (!(comment instanceof BlockComment) || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
                || !startsOnSameLine.test(comment, lambdaExpr)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        comments.comment(comment),
                        Doc.text(" "),
                        lambdaExpression(lambdaExpr))),
                Doc.HARD_LINE,
                Doc.text(")")));
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
        Optional<HuggableExpressionLambdaArgument> huggable = huggableExpressionLambdaArgument(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        HuggableExpressionLambdaArgument argument = huggable.orElseThrow();
        LambdaExpr lambdaExpr = argument.lambdaExpr();
        Optional<LambdaExpr> nestedLambda = argument.nestedLambda();
        String parameters = lambdaParameters(lambdaExpr);
        String firstLine = argument.firstLine();
        Expression bodyExpression = argument.bodyExpression();
        if (nestedLambda.isPresent()) {
            Doc bodyDoc = huggableExpressionLambdaBody(firstLine, bodyExpression);
            if (bodyFirstSourceLineFits(firstLine, bodyExpression)) {
                return Optional.of(Doc.concat(
                        Doc.text(prefix + "("),
                        Doc.indent(Doc.concat(
                                Doc.HARD_LINE,
                                Doc.text(huggableExpressionLambdaFirstLine(lambdaExpr, parameters) + " "),
                                Doc.indent(bodyDoc))),
                        Doc.HARD_LINE,
                        Doc.text(")")));
            }
            return Optional.of(Doc.concat(
                    Doc.text(prefix + "("),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            Doc.text(huggableExpressionLambdaFirstLine(lambdaExpr, parameters)),
                            Doc.indent(Doc.concat(
                                    Doc.HARD_LINE,
                                    bodyDoc)))),
                    Doc.HARD_LINE,
                    Doc.text(")")));
        }
        Doc bodyDoc = huggableExpressionLambdaBody(firstLine, bodyExpression);
        if (logicalBinaryBody(bodyExpression).isPresent()) {
            return Optional.of(Doc.concat(
                    Doc.text(firstLine),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, bodyDoc)),
                    Doc.HARD_LINE,
                    Doc.text(")")));
        }
        Optional<Doc> packedObjectCreation = packedObjectCreationWithoutClosingLine(firstLine, bodyExpression);
        if (packedObjectCreation.isPresent()) {
            return Optional.of(Doc.concat(
                    Doc.text(firstLine + " "),
                    Doc.indent(packedObjectCreation.orElseThrow()),
                    Doc.HARD_LINE,
                    Doc.text("))")));
        }
        Optional<Doc> packedBodyCall = packedBodyCallWithoutClosingLine(firstLine, bodyExpression);
        if (packedBodyCall.isPresent()) {
            return Optional.of(Doc.concat(
                    Doc.text(firstLine + " "),
                    Doc.indent(packedBodyCall.orElseThrow()),
                    Doc.HARD_LINE,
                    Doc.text("))")));
        }
        Optional<Doc> packedBodyCallScope = packedBodyCallScopeWithoutClosingLine(firstLine, bodyExpression);
        if (packedBodyCallScope.isPresent()) {
            return Optional.of(Doc.concat(
                    Doc.text(firstLine + " "),
                    Doc.indent(packedBodyCallScope.orElseThrow()),
                    Doc.HARD_LINE,
                    Doc.text("))")));
        }
        if (bodyFirstSourceLineFits(firstLine, bodyExpression)) {
            return Optional.of(Doc.concat(
                    Doc.text(firstLine + " "),
                    Doc.indent(bodyDoc),
                    Doc.HARD_LINE,
                    Doc.text(")")));
        }
        if (bodyExpression instanceof MethodCallExpr methodCall
                && lambdaMethodCallBodyWithOpener(parameters, methodCall).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(firstLine),
                Doc.indent(Doc.concat(Doc.HARD_LINE, bodyDoc)),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    Optional<String> huggableExpressionLambdaFirstLine(String prefix, NodeList<Expression> arguments) {
        return huggableExpressionLambdaArgument(prefix, arguments).map(HuggableExpressionLambdaArgument::firstLine);
    }

    private Optional<HuggableExpressionLambdaArgument> huggableExpressionLambdaArgument(
            String prefix,
            NodeList<Expression> arguments) {
        int lambdaIndex = expressionLambdaArgumentIndex(arguments);
        if (lambdaIndex < 0 || lambdaIndex < arguments.size() - 1 || hasOtherLambdaArgument(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = (LambdaExpr) arguments.get(lambdaIndex);
        Optional<Expression> body = lambdaExpr.getExpressionBody();
        Optional<LambdaExpr> nestedLambda = body
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast);
        if (body.isEmpty()
                || !lambdaExpr.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String parameters = lambdaParameters(lambdaExpr);
        if (lambdaParametersShouldBreak(lambdaExpr, parameters)) {
            return Optional.empty();
        }
        if (nestedLambda.isPresent()) {
            LambdaExpr nested = nestedLambda.orElseThrow();
            if (!nested.getAllContainedComments().isEmpty()
                    || lambdaParametersShouldBreak(nested, lambdaParameters(nested))) {
                return Optional.empty();
            }
        }
        String leadingArguments = compactJoin.apply(arguments.subList(0, lambdaIndex));
        String lambdaFirstLine = huggableExpressionLambdaFirstLine(lambdaExpr, parameters);
        String firstLine = prefix + "("
                + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
                + lambdaFirstLine;
        String flat = prefix + "(" + compactJoin.apply(arguments) + ")";
        boolean sourceMultilineLogicalBody = body.filter(this::sourceMultilineLogicalBody).isPresent();
        if ((!sourceMultilineLogicalBody
                        && huggableExpressionFirstLineWidth(flat) < options.lineWidth())
                || huggableExpressionLineWidth(firstLine, lambdaExpr, lambdaFirstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        Optional<Expression> bodyExpressionCandidate = huggableExpressionLambdaBodyExpression(lambdaExpr);
        if (bodyExpressionCandidate.isEmpty()) {
            return Optional.empty();
        }
        Expression bodyExpression = bodyExpressionCandidate.orElseThrow();
        if (!huggableExpressionLambdaBody(bodyExpression)
                && !huggableOverflowingMethodCallBody(firstLine, bodyExpression)) {
            return Optional.empty();
        }
        if (bodyExpression instanceof MethodCallExpr methodCall
                && lambdaMethodCallBodyWithOpener(parameters, methodCall).isPresent()
                && packedBodyCallWithoutClosingLine(firstLine, bodyExpression).isEmpty()
                && packedBodyCallScopeWithoutClosingLine(firstLine, bodyExpression).isEmpty()
                && !bodyFirstSourceLineFits(firstLine, bodyExpression)) {
            return Optional.empty();
        }
        return Optional.of(new HuggableExpressionLambdaArgument(lambdaExpr, nestedLambda, bodyExpression, firstLine));
    }

    private record HuggableExpressionLambdaArgument(
            LambdaExpr lambdaExpr,
            Optional<LambdaExpr> nestedLambda,
            Expression bodyExpression,
            String firstLine) {}

    private Doc huggableExpressionLambdaBody(String firstLine, Expression bodyExpression) {
        Optional<Doc> logicalBody = logicalBinaryBodyDoc(bodyExpression);
        if (logicalBody.isPresent()) {
            return logicalBody.orElseThrow();
        }
        if (bodyExpression instanceof MethodCallExpr methodCall
                && methodCall.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
                && (bodyFirstSourceLineOverflows(firstLine, methodCall)
                        || bodyCompactLineOverflows(firstLine, methodCall))) {
            return brokenMethodCallRenderer.apply(methodCall);
        }
        return expressionRenderer.format(bodyExpression);
    }

    private int huggableExpressionFirstLineWidth(String firstLine) {
        return blockStatementWidth.applyAsInt(options.indentUnit() + firstLine);
    }

    private int huggableExpressionLineWidth(String line, LambdaExpr lambdaExpr, String lambdaText) {
        int lambdaOffset = line.indexOf(lambdaText);
        if (lambdaOffset < 0) {
            return huggableExpressionFirstLineWidth(line);
        }
        return lambdaExpr.getRange()
                .map(range -> Math.max(0, range.begin.column + 1 - lambdaOffset) + line.length())
                .orElseGet(() -> huggableExpressionFirstLineWidth(line));
    }

    private Optional<Doc> packedBodyCallWithoutClosingLine(String firstLine, Expression bodyExpression) {
        if (!(bodyExpression instanceof MethodCallExpr methodCall)
                || methodCall.getArguments().isEmpty()
                || methodCall.getScope().filter(scope -> rawSource.rawWithoutOwnComment(scope).contains("\n")).isPresent()) {
            return Optional.empty();
        }
        String opener = methodCallPrefix(methodCall) + "(";
        if (huggableExpressionFirstLineWidth(firstLine + " " + opener) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(opener),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)))));
    }

    private Optional<Doc> packedObjectCreationWithoutClosingLine(String firstLine, Expression bodyExpression) {
        if (!(bodyExpression instanceof ObjectCreationExpr objectCreation)
                || objectCreation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String opener = objectCreationPrefix(objectCreation) + "(";
        if (huggableExpressionFirstLineWidth(firstLine + " " + opener) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(opener),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(objectCreation.getArguments(), Doc.HARD_LINE)))));
    }

    private Optional<Doc> packedBodyCallScopeWithoutClosingLine(String firstLine, Expression bodyExpression) {
        if (!(bodyExpression instanceof MethodCallExpr methodCall)
                || methodCall.getScope().isEmpty()) {
            return Optional.empty();
        }
        String scope = compact.apply(methodCall.getScope().orElseThrow());
        String bodyFirstLine = bodyFirstSourceLine(bodyExpression);
        if (!bodyFirstLine.endsWith("(") && !bodyFirstLine.equals(scope)) {
            return Optional.empty();
        }
        if (huggableExpressionFirstLineWidth(firstLine + " " + scope) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(scope),
                Doc.HARD_LINE,
                Doc.text("." + methodCallSelector(methodCall) + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)))));
    }

    private String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
                + expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
    }

    private String methodCallSelector(MethodCallExpr expression) {
        return expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
    }

    private String objectCreationPrefix(ObjectCreationExpr expression) {
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
                + "new "
                + expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                        .orElse("")
                + compact.apply(expression.getType());
    }

    private boolean bodyFirstSourceLineFits(String firstLine, Expression bodyExpression) {
        return rawSource.rawWithoutOwnComment(bodyExpression).contains("\n")
                && huggableExpressionFirstLineWidth(firstLine + " " + bodyFirstSourceLine(bodyExpression)) <= options.lineWidth();
    }

    private boolean bodyFirstSourceLineOverflows(String firstLine, MethodCallExpr methodCall) {
        return huggableExpressionFirstLineWidth(firstLine + " " + bodyFirstSourceLine(methodCall)) > options.lineWidth();
    }

    private boolean bodyCompactLineOverflows(String firstLine, MethodCallExpr methodCall) {
        return huggableExpressionFirstLineWidth(firstLine + " " + compact.apply(methodCall)) > options.lineWidth();
    }

    private String bodyFirstSourceLine(Node node) {
        return rawSource.rawWithoutOwnComment(node)
                .strip()
                .lines()
                .findFirst()
                .orElse("");
    }

    private boolean sourceMultilineLogicalBody(Expression body) {
        return logicalBinaryBody(body).isPresent() && rawSource.rawWithoutOwnComment(body).contains("\n");
    }

    private boolean sourceMultilineMethodCallBody(Expression body) {
        return body instanceof MethodCallExpr && rawSource.rawWithoutOwnComment(body).contains("\n");
    }

    private boolean huggableExpressionLambdaBody(Expression body) {
        if (body instanceof MethodCallExpr) {
            return !((MethodCallExpr) body).getArguments().isEmpty();
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
        if (body instanceof LambdaExpr lambdaExpr && lambdaExpr.getExpressionBody().isPresent()) {
            return huggableExpressionLambdaBody(lambdaExpr.getExpressionBody().orElseThrow());
        }
        return false;
    }

    private boolean huggableOverflowingMethodCallBody(String firstLine, Expression body) {
        return body instanceof MethodCallExpr methodCall
                && methodCall.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
                && (bodyFirstSourceLineOverflows(firstLine, methodCall)
                        || bodyCompactLineOverflows(firstLine, methodCall));
    }

    private String huggableExpressionLambdaFirstLine(LambdaExpr lambdaExpr, String parameters) {
        return lambdaExpr.getExpressionBody()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .map(nested -> parameters + " -> " + lambdaParameters(nested) + " ->")
                .orElse(parameters + " ->");
    }

    private Optional<Expression> huggableExpressionLambdaBodyExpression(LambdaExpr lambdaExpr) {
        return lambdaExpr.getExpressionBody().flatMap(body -> {
            if (body instanceof MethodCallExpr
                    || body instanceof ObjectCreationExpr
                    || body instanceof ConditionalExpr) {
                return Optional.of(body);
            }
            if (logicalBinaryBody(body).isPresent()) {
                return Optional.of(body);
            }
            if (body instanceof LambdaExpr nested) {
                return huggableExpressionLambdaBodyExpression(nested);
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

    private int blockLambdaArgumentIndex(NodeList<Expression> arguments) {
        int lambdaIndex = -1;
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof LambdaExpr lambdaExpr && lambdaExpr.getBody().isBlockStmt()) {
                if (lambdaIndex >= 0) {
                    return -1;
                }
                lambdaIndex = i;
            }
        }
        return lambdaIndex;
    }

    private boolean hasOtherLambdaArgument(NodeList<Expression> arguments, int lambdaIndex) {
        for (int i = 0; i < arguments.size(); i++) {
            if (i != lambdaIndex && arguments.get(i) instanceof LambdaExpr) {
                return true;
            }
        }
        return false;
    }
}
