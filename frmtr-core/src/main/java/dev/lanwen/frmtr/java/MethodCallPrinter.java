package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
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
        if (!breakMode.isForced()) {
            Optional<Doc> chain = methodCallChain(expression);
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

    Optional<Doc> sourceMultilineSingleObjectCreationArgumentStatement(
            MethodCallExpr expression,
            ExpressionStmt statement) {
        return methodChains.sourceMultilineSingleObjectCreationArgumentStatement(expression, statement);
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
                || !sourceShape.methodCallArgumentsSpanMultipleLines(expression)) {
            return Optional.empty();
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
                docs.add(argument instanceof ObjectCreationExpr ? line : Doc.concat(Doc.text(","), line));
            }
        }
        return Doc.concat(docs);
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
        if (argument instanceof ObjectCreationExpr objectCreation && !suffix.isEmpty()) {
            return objectCreationWithSuffix.apply(objectCreation, suffix);
        }
        if (!(argument instanceof BinaryExpr binaryExpr)
                || !argument.getAllContainedComments().isEmpty()
                || continuationStatementWidth.applyAsInt(compactSource.compact(binaryExpr)) <= options.lineWidth()) {
            return expressionRenderer.apply(argument);
        }
        return Doc.ifBreak(
                brokenBinaryExpressionLinesRenderer.apply(binaryExpr),
                expressionRenderer.apply(argument));
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
