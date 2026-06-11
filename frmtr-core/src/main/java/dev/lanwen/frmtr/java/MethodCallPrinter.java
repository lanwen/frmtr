package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Prints method calls and method-call chains after expression dispatch has selected a call.
 *
 * <p>This helper owns the call-specific decision tree: auto versus forced chain breaks, compact root plus broken final
 * segment handling, mixed field/method chains, name comments on chain segments, empty argument comments, text-block
 * arguments, and over-wide binary arguments. The boundary exists so {@link JavaPrinter} can keep broad expression
 * dispatch, enclosed suffix breaking, and binary-expression policy in their current owners while object creation stays
 * in {@link ObjectCreationPrinter}, lambda argument rendering stays in {@link LambdaExpressionPrinter}, commented
 * argument lists stay in {@link CommentedExpressionListPrinter}, and method-call layout reads as one state machine.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/member_chain/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/member_chain/frmtr.output.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/text-blocks/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/text-blocks/frmtr.output.java}; lambda call
 * cases are covered by the two {@code lambda/arrow-parens-*} fixture directories.
 */
final class MethodCallPrinter {
    private final CommentTracker comments;
    private final JavaCommentPlacementPolicy commentPlacement;
    private final SourceShape sourceShape;
    private final FormatterOptions options;
    private final CompactSourceText compactSource;
    private final CommentedExpressionListPrinter commentedExpressionLists;
    private final MethodCallChainPrinter methodChains;
    private final Function<Expression, Doc> expressionRenderer;
    private final BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix;
    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;
    private final Function<BinaryExpr, Doc> brokenBinaryExpressionLinesRenderer;
    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;
    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;
    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments;
    private final BiFunction<String, NodeList<Expression>, Optional<ExpressionLambdaArgumentLayout.Plan>> expressionLambdaArgumentPlan;
    private final Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> continuationStatementWidth;
    private final ToIntFunction<String> blockStatementWidth;

    MethodCallPrinter(
            JavaFormatContext context,
            TypePrinter types,
            Function<Expression, Doc> expressionRenderer,
            BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<ExpressionLambdaArgumentLayout.Plan>> expressionLambdaArgumentPlan,
            Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer,
            Function<BinaryExpr, Doc> brokenBinaryExpressionLinesRenderer,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth,
            ToIntFunction<String> blockStatementWidth) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.sourceShape = context.sourceShape;
        this.options = context.options;
        this.compactSource = context.compactSource;
        this.commentedExpressionLists = new CommentedExpressionListPrinter(context, expressionRenderer);
        this.methodChains = new MethodCallChainPrinter(
                context,
                this,
                types,
                this.commentedExpressionLists,
                expressionRenderer,
                brokenObjectCreationRenderer,
                objectCreationPrefix,
                huggableBlockLambdaArguments,
                huggableBlockLambdaFirstLine,
                commentedExpressionLambdaArgument,
                huggableExpressionLambdaArguments,
                expressionLambdaArgumentPlan,
                currentIndentedWidth,
                continuationStatementWidth,
                blockStatementWidth);
        this.expressionRenderer = expressionRenderer;
        this.brokenEnclosedForSuffix = brokenEnclosedForSuffix;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.brokenBinaryExpressionLinesRenderer = brokenBinaryExpressionLinesRenderer;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.expressionLambdaArgumentPlan = expressionLambdaArgumentPlan;
        this.unformattedTextBlockRenderer = unformattedTextBlockRenderer;
        this.currentIndentedWidth = currentIndentedWidth;
        this.continuationStatementWidth = continuationStatementWidth;
        this.blockStatementWidth = blockStatementWidth;
    }

    Doc methodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallBreakMode.AUTO);
    }

    Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallBreakMode.FORCED);
    }

    Optional<Doc> packedExpressionLambdaMethodCallChainBody(String firstLine, MethodCallExpr expression) {
        return methodChains.packedExpressionLambdaBodyChain(firstLine, expression);
    }

    /**
     * Chooses the method-call shape once callers know this expression really is a method call.
     *
     * <p>The unforced path tries a chain shape only when the call itself asks for it; the forced path is used by
     * surrounding expression printers that already decided the call arguments must break.
     */
    private Doc methodCall(MethodCallExpr expression, MethodCallBreakMode breakMode) {
        if (expression.getScope().isEmpty()
                && expression.getNameAsString().equals("yield")
                && !expression.getArguments().isEmpty()) {
            return Doc.text("yield (" + compactSource.compactJoin(expression.getArguments()) + ")");
        }
        if (expression.getScope().filter(this::shouldPrintScopeAsDoc).isPresent()) {
            Expression scope = expression.getScope().orElseThrow();
            Doc call = methodCallWithoutScope(expression);
            if (scope instanceof TextBlockLiteralExpr) {
                call = Doc.indent(call);
            }
            return Doc.concat(
                    expressionRenderer.apply(scope),
                    Doc.text("."),
                    call);
        }
        Optional<Doc> sourceMultilineExpressionLambda = sourceMultilineExpressionLambda(expression);
        if (sourceMultilineExpressionLambda.isPresent()) {
            return sourceMultilineExpressionLambda.orElseThrow();
        }
        if (!breakMode.isForced()) {
            Optional<Doc> chain = methodCallChain(expression);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        } else if (methodCallChainIsSourceMultiline(expression)) {
            Optional<Doc> chain = methodCallChain(expression, breakMode, "");
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        Optional<Doc> sourceMultilineArguments = sourceMultilineArguments(expression);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> suffixedEnclosed = suffixedEnclosedMethodCall(expression, false);
        if (suffixedEnclosed.isPresent()) {
            return suffixedEnclosed.orElseThrow();
        }
        String prefix = methodCallPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return commentedArguments.orElseThrow();
            }
            return Doc.text(prefix + "()");
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        Optional<Doc> commentedExpressionLambda = commentedExpressionLambdaArgument.apply(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return commentedExpressionLambda.orElseThrow();
        }
        Optional<Doc> huggableExpressionLambda = huggableExpressionLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        Optional<Doc> brokenExpressionLambdaArguments = brokenExpressionLambdaArgumentsForOverflow(prefix, expression);
        if (brokenExpressionLambdaArguments.isPresent()) {
            return brokenExpressionLambdaArguments.orElseThrow();
        }
        Optional<Doc> singleTextBlockArgument = singleTextBlockArgument(prefix, expression);
        if (singleTextBlockArgument.isPresent()) {
            return singleTextBlockArgument.orElseThrow();
        }
        Optional<Doc> singleBinaryArgument = singleBinaryArgument(prefix, expression.getArguments(), breakMode);
        if (singleBinaryArgument.isPresent()) {
            return singleBinaryArgument.orElseThrow();
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        Doc call = Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        methodCallLine(breakMode),
                        methodCallArgumentList(expression.getArguments(), Doc.LINE))),
                methodCallLine(breakMode),
                Doc.text(")"));
        return breakMode.isForced() ? call : Doc.group(call);
    }

    private Optional<Doc> brokenExpressionLambdaArgumentsForOverflow(String prefix, MethodCallExpr expression) {
        Optional<ExpressionLambdaArgumentLayout.Plan> plan =
                expressionLambdaArgumentPlan.apply(prefix, expression.getArguments());
        if (plan.filter(argument -> expressionLambdaBodyOpenerOverflows(expression, argument))
                .isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList(expression.getArguments(), Doc.HARD_LINE))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compactSource.compact(scope) + ".").orElse("")
                + expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
    }

    Doc methodCallWithoutScope(MethodCallExpr expression) {
        String prefix = expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return commentedArguments.orElseThrow();
            }
            return Doc.text(prefix + "()");
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        return Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        methodCallArgumentList(expression.getArguments(), Doc.LINE))),
                Doc.SOFT_LINE,
                Doc.text(")")));
    }

    Optional<Doc> suffixedEnclosedMethodCall(MethodCallExpr expression, boolean leadingBreak) {
        return expression.getScope()
                .filter(EnclosedExpr.class::isInstance)
                .map(EnclosedExpr.class::cast)
                .filter(scope -> leadingBreak
                        || blockStatementWidth.applyAsInt(compactSource.compact(expression) + ";") > options.lineWidth())
                .map(scope -> Doc.concat(
                        brokenEnclosedForSuffix.apply(scope, leadingBreak),
                        Doc.text("."),
                        methodCallWithoutScope(expression)));
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodChains.methodCallChain(expression);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return methodChains.forcedMethodCallChain(expression);
    }

    Optional<Doc> forcedMethodCallChainWithSemicolon(MethodCallExpr expression) {
        return methodChains.forcedMethodCallChainWithSemicolon(expression);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        return methodChains.compactRootWithBrokenFinalChainSegment(expression);
    }

    Optional<Doc> sourceMultilineMethodCallStatement(
            MethodCallExpr expression,
            ExpressionStmt statement) {
        return methodChains.sourceMultilineMethodCallStatement(expression, statement);
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        return methodChains.methodCallChain(expression, force);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix) {
        return methodChains.methodCallChain(expression, breakMode, finalSegmentSuffix);
    }

    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        return methodChains.mixedFieldMethodCallChain(expression);
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        return methodChains.mixedFieldMethodCallRoot(expression);
    }

    boolean methodCallChainHasComments(MethodCallExpr expression) {
        return methodChains.methodCallChainHasComments(expression);
    }

    boolean methodCallChainHasFinalTrailingLineComment(MethodCallExpr expression) {
        return methodChains.methodCallChainHasFinalTrailingLineComment(expression);
    }

    boolean methodCallChainIsSourceMultiline(MethodCallExpr expression) {
        return methodChains.methodCallChainIsSourceMultiline(expression);
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodChains.methodCallChainRootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodChains.methodCallChainRootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        return methodChains.methodCallChainFirstLine(expression);
    }

    /**
     * Rebuilds empty argument lists that contain comments JavaParser exposes outside the argument list.
     *
     * <p>For calls like {@code call( // note )}, JavaParser can attach the line comment to the call or its scope rather
     * than to a missing argument node, so this method gathers those source-line comments and orphan comments before
     * deciding the call is really empty.
     */
    Optional<Doc> emptyMethodCallArguments(String prefix, MethodCallExpr expression) {
        List<Doc> argumentComments = new ArrayList<>();
        Doc firstArgumentComment = comments.ownComment(expression, comment -> comment instanceof LineComment
                && CommentIndex.startsOnBeginLine(comment, expression)
                && CommentIndex.startsBeforeEnd(comment, expression));
        if (firstArgumentComment != Doc.EMPTY) {
            argumentComments.add(firstArgumentComment);
        }
        expression.getScope()
                .map(scope -> comments.ownComment(scope, comment -> comment instanceof LineComment
                        && CommentIndex.startsOnBeginLine(comment, expression)
                        && CommentIndex.startsBeforeEnd(comment, expression)))
                .filter(comment -> comment != Doc.EMPTY)
                .ifPresent(argumentComments::add);
        argumentComments.addAll(comments.orphanCommentStatements(expression));
        if (argumentComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentComments))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    /**
     * Keeps a single text block visually isolated from the call prefix and closing parenthesis.
     *
     * <p>Text blocks already own their internal indentation, so grouping them like ordinary arguments makes trailing
     * comments and the closing parenthesis harder to place predictably.
     */
    private Optional<Doc> singleTextBlockArgument(String prefix, MethodCallExpr expression) {
        if (expression.getArguments().size() != 1
                || !(expression.getArguments().get(0) instanceof TextBlockLiteralExpr textBlockLiteralExpr)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, textBlockArgument(textBlockLiteralExpr, expression))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    Optional<Doc> sourceMultilineArguments(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty()
                || !expression.getAllContainedComments().isEmpty()
                || hasHuggableExpressionLambdaArgument(expression)
                || !sourceShape.methodCallArgumentsSpanMultipleLines(expression)) {
            return Optional.empty();
        }
        Optional<Doc> scopedPrefix = sourceMultilineArgumentScopedPrefix(expression);
        if (scopedPrefix.isPresent()) {
            return Optional.of(Doc.concat(
                    scopedPrefix.orElseThrow(),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            methodCallArgumentList(expression.getArguments(), Doc.HARD_LINE))),
                    Doc.HARD_LINE,
                    Doc.text(")")));
        }
        String prefix = methodCallPrefix(expression);
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList(expression.getArguments(), Doc.HARD_LINE))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    Optional<Doc> sourceMultilineExpressionLambda(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty()
                || !expression.getAllContainedComments().isEmpty()
                || sourceMultilineChainWithConditionalLambda(expression)
                || sourceMultilineMethodCallScope(expression)
                || !expressionLambdaStartsOnSelectorLine(expression)
                || !expressionLambdaSpansMultipleLines(expression)) {
            return Optional.empty();
        }
        String prefix = methodCallPrefix(expression);
        Optional<ExpressionLambdaArgumentLayout.Plan> plan =
                expressionLambdaArgumentPlan.apply(prefix, expression.getArguments());
        if (plan.filter(argument -> argument.firstLineFits(
                                line -> methodCallRootLineWidth(expression, line),
                                options.lineWidth()))
                        .isEmpty()
                || plan.filter(argument -> expressionLambdaBodyOpenerOverflows(expression, argument))
                        .isPresent()) {
            return Optional.empty();
        }
        return huggableExpressionLambdaArguments.apply(prefix, expression.getArguments());
    }

    private boolean sourceMultilineMethodCallScope(MethodCallExpr expression) {
        return methodCallChainIsSourceMultiline(expression)
                && expression.getScope().filter(MethodCallExpr.class::isInstance).isPresent();
    }

    private boolean sourceMultilineChainWithConditionalLambda(MethodCallExpr expression) {
        return methodCallChainIsSourceMultiline(expression)
                && expression.getArguments().stream()
                        .filter(LambdaExpr.class::isInstance)
                        .map(LambdaExpr.class::cast)
                        .flatMap(lambda -> lambda.getExpressionBody().stream())
                        .anyMatch(ConditionalExpr.class::isInstance);
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            MethodCallExpr expression,
            ExpressionLambdaArgumentLayout.Plan argument) {
        return !argument.bodyFirstSourceLineFits()
                && argument.bodyOpenerFitsOnContinuation(continuationStatementWidth, options.lineWidth())
                && argument.bodyOpenerOverflows(
                        line -> methodCallRootLineWidth(expression, line),
                        options.lineWidth());
    }

    private int methodCallRootLineWidth(MethodCallExpr expression, String firstLine) {
        return expression.getRange()
                .map(range -> Math.max(0, range.begin.column + 1) + firstLine.length())
                .orElseGet(() -> currentIndentedWidth.applyAsInt(firstLine));
    }

    private boolean expressionLambdaStartsOnSelectorLine(MethodCallExpr expression) {
        Optional<Integer> selectorLine = expression.getName().getRange().map(range -> range.begin.line);
        if (selectorLine.isEmpty()) {
            return false;
        }
        return expression.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambda -> lambda.getExpressionBody().isPresent())
                .flatMap(lambda -> lambda.getRange().stream())
                .anyMatch(range -> range.begin.line == selectorLine.orElseThrow());
    }

    private boolean hasHuggableExpressionLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments().stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getExpressionBody().filter(this::huggableExpressionLambdaBody).isPresent());
    }

    private boolean huggableExpressionLambdaBody(Expression body) {
        if (body instanceof MethodCallExpr || body instanceof ConditionalExpr) {
            return true;
        }
        if (logicalBinaryBody(body).isPresent()) {
            return true;
        }
        if (body instanceof LambdaExpr lambdaExpr) {
            return lambdaExpr.getExpressionBody().filter(this::huggableExpressionLambdaBody).isPresent();
        }
        return false;
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

    private boolean expressionLambdaSpansMultipleLines(MethodCallExpr expression) {
        return expression.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambda -> lambda.getExpressionBody().isPresent())
                .flatMap(lambda -> lambda.getRange().stream())
                .anyMatch(range -> range.begin.line < range.end.line);
    }

    /**
     * Keeps a source-multiline method-call scope structured when a later call's arguments force their own multiline
     * layout, instead of compacting that scope into the later call prefix.
     */
    private Optional<Doc> sourceMultilineArgumentScopedPrefix(MethodCallExpr expression) {
        return expression.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(sourceShape::spansMultipleLines)
                .map(scope -> Doc.concat(
                        expressionRenderer.apply(scope),
                        Doc.text("." + methodCallSelector(expression) + "(")));
    }

    private String methodCallSelector(MethodCallExpr expression) {
        return expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
    }

    private Doc textBlockArgument(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        Doc leading = comments.ownComment(textBlockLiteralExpr, LineComment.class::isInstance);
        Doc literal = Doc.text(unformattedTextBlockRenderer.apply(textBlockLiteralExpr));
        Doc trailing = textBlockSameLineTrailingComment(textBlockLiteralExpr, expression);
        if (leading != Doc.EMPTY) {
            return Doc.concat(leading, Doc.HARD_LINE, literal, trailing);
        }
        return Doc.concat(literal, trailing);
    }

    Doc methodCallArgumentList(NodeList<Expression> arguments, Doc line) {
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            Expression argument = arguments.get(index);
            boolean last = index == arguments.size() - 1;
            docs.add(methodCallArgumentDoc(argument, last ? "" : ","));
            if (!last) {
                docs.add(argumentConsumesSuffix(argument) ? line : Doc.concat(Doc.text(","), line));
            }
        }
        return Doc.concat(docs);
    }

    private boolean argumentConsumesSuffix(Expression argument) {
        return argument instanceof ObjectCreationExpr || argument instanceof MethodCallExpr;
    }

    /**
     * Keeps a wide binary expression breakable when it appears as one argument in a method-call argument list.
     *
     * <p>The ordinary expression renderer owns the flat binary spelling. Once the surrounding call list breaks, the
     * binary-expression helper owns the continuation lines so long string concatenations do not collapse back onto an
     * over-wide argument line.
     */
    private Doc methodCallArgumentDoc(Expression argument) {
        return methodCallArgumentDoc(argument, "");
    }

    private Doc methodCallArgumentDoc(Expression argument, String suffix) {
        if (argument instanceof MethodCallExpr methodCall && !suffix.isEmpty()) {
            Optional<Doc> compact = compactMethodCallArgumentWithSuffix(methodCall, suffix);
            if (compact.isPresent()) {
                return compact.orElseThrow();
            }
            Optional<Doc> chain = methodCallChain(methodCall, MethodCallBreakMode.FORCED, suffix);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
            return Doc.concat(expressionRenderer.apply(methodCall), Doc.text(suffix));
        }
        if (argument instanceof ObjectCreationExpr objectCreation && !suffix.isEmpty()) {
            return objectCreationWithSuffix.apply(objectCreation, suffix);
        }
        if (!(argument instanceof BinaryExpr binaryExpr)
                || !argument.getAllContainedComments().isEmpty()
                || continuationStatementWidth.applyAsInt(compactSource.compact(binaryExpr)) <= options.lineWidth()) {
            return expressionRenderer.apply(argument);
        }
        return Doc.ifBreak(brokenBinaryExpressionLinesRenderer.apply(binaryExpr), expressionRenderer.apply(argument));
    }

    private Optional<Doc> compactMethodCallArgumentWithSuffix(MethodCallExpr expression, String suffix) {
        List<JavaCommentTrivia> trailingComments = methodCallArgumentTrailingLineComments(expression);
        if (trailingComments.isEmpty() && sourceShape.spansMultipleLines(expression)) {
            return Optional.empty();
        }
        if (!trailingComments.isEmpty() && hasNonTrailingContainedComments(expression, trailingComments)) {
            return Optional.empty();
        }
        String code = (trailingComments.isEmpty() ? compactSource.compact(expression) : compactSource.commentFree(expression)) + suffix;
        if (continuationStatementWidth.applyAsInt(code) > options.lineWidth()) {
            return Optional.empty();
        }
        List<Doc> renderedTrailing = trailingComments.stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        if (renderedTrailing.isEmpty()) {
            return Optional.of(Doc.text(code));
        }
        return Optional.of(Doc.concat(Doc.text(code), Doc.text(" "), Doc.join(Doc.text(" "), renderedTrailing)));
    }

    private boolean hasNonTrailingContainedComments(
            MethodCallExpr expression,
            List<JavaCommentTrivia> trailingComments) {
        return expression.getAllContainedComments().stream()
                .anyMatch(comment -> trailingComments.stream()
                        .noneMatch(trailing -> trailing.comment() == comment));
    }

    private List<JavaCommentTrivia> methodCallArgumentTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression).stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsOnEndLine(expression) || comment.startsAfterNodeOnSameLine(expression))
                .filter(comment -> sourceComments.stream()
                        .noneMatch(existing -> existing.comment() == comment.comment()))
                .forEach(sourceComments::add);
        int endLine = CommentIndex.endLine(expression, Integer.MIN_VALUE);
        expression.getAllContainedComments().stream()
                .filter(LineComment.class::isInstance)
                .map(JavaCommentTrivia::from)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) == endLine)
                .filter(comment -> sourceComments.stream()
                        .noneMatch(existing -> existing.comment() == comment.comment()))
                .forEach(sourceComments::add);
        return sourceComments;
    }

    private Doc textBlockSameLineTrailingComment(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        return expression.getOrphanComments().stream()
                .filter(LineComment.class::isInstance)
                .filter(comment -> CommentIndex.startsOnEndLine(textBlockLiteralExpr, comment))
                .findFirst()
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
    }

    /**
     * Breaks a single binary argument under the call when the flat call no longer fits.
     *
     * <p>Binary expressions have their own continuation policy, so the call printer only decides that the binary
     * argument gets the entire broken argument list to itself.
     */
    private Optional<Doc> singleBinaryArgument(
            String prefix,
            NodeList<Expression> arguments,
            MethodCallBreakMode breakMode) {
        if (arguments.size() != 1 || !(arguments.get(0) instanceof BinaryExpr binaryExpr)) {
            return Optional.empty();
        }
        if (!breakMode.isForced()
                && currentIndentedWidth.applyAsInt(prefix + "(" + compactSource.compact(binaryExpr) + ")")
                        <= options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenBinaryExpressionLinesRenderer.apply(binaryExpr))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    Optional<Doc> assignmentWithBrokenMethodCallArguments(AssignExpr assignExpr, MethodCallExpr methodCall) {
        return assignmentWithBrokenMethodCallArguments(assignExpr, methodCall, "");
    }

    Optional<Doc> assignmentWithBrokenMethodCallArgumentsAndSemicolon(AssignExpr assignExpr, MethodCallExpr methodCall) {
        return assignmentWithBrokenMethodCallArguments(assignExpr, methodCall, ";");
    }

    private Optional<Doc> assignmentWithBrokenMethodCallArguments(
            AssignExpr assignExpr,
            MethodCallExpr methodCall,
            String finalSegmentSuffix) {
        if (methodCall.getArguments().isEmpty() || !methodCall.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (methodCallChainIsSourceMultiline(methodCall)) {
            String firstLine = compactSource.compact(assignExpr.getTarget()) + " "
                    + assignExpr.getOperator().asString()
                    + " "
                    + methodCallChainFirstLine(methodCall);
            Optional<Doc> chain = blockStatementWidth.applyAsInt(firstLine + ";") <= options.lineWidth()
                    ? methodCallChain(methodCall, MethodCallBreakMode.FORCED, finalSegmentSuffix)
                    : Optional.empty();
            if (chain.isPresent()) {
                return Optional.of(Doc.concat(
                        expressionRenderer.apply(assignExpr.getTarget()),
                        Doc.text(" " + assignExpr.getOperator().asString() + " "),
                        chain.orElseThrow()));
            }
        }
        String firstLine = compactSource.compact(assignExpr.getTarget()) + " "
                + assignExpr.getOperator().asString()
                + " "
                + methodCallPrefix(methodCall)
                + "(";
        if (blockStatementWidth.applyAsInt(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                expressionRenderer.apply(assignExpr.getTarget()),
                Doc.text(" " + assignExpr.getOperator().asString() + " "),
                brokenMethodCall(methodCall),
                Doc.text(finalSegmentSuffix)));
    }

    boolean shouldPrintScopeAsDoc(Expression expression) {
        return expression instanceof ArrayCreationExpr
                || expression instanceof ArrayAccessExpr
                || expression instanceof TextBlockLiteralExpr
                || expression instanceof EnclosedExpr enclosedExpr
                        && enclosedExpr.getInner() instanceof CastExpr;
    }

    private Doc methodCallLine(MethodCallBreakMode breakMode) {
        return breakMode.argumentLine();
    }

}
