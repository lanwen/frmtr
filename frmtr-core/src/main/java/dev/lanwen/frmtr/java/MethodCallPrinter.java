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
 * {@code frmtr-core/src/test/resources/format/method-chain-member-access/input.java} with
 * {@code frmtr-core/src/test/resources/format/method-chain-member-access/frmtr-default.output.java} and
 * {@code frmtr-core/src/test/resources/format/text-block-raw-method-call/input.java} with
 * {@code frmtr-core/src/test/resources/format/text-block-raw-method-call/frmtr-default.output.java}; lambda call
 * cases are covered by the two {@code lambda/arrow-parens-*} fixture directories.
 */
final class MethodCallPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShape sourceShape;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final CompactSourceText compactSource;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final MethodCallChainPrinter methodChains;

    private final Function<Expression, Doc> expressionRenderer;

    private final BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix;

    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;

    private final Function<Expression, Optional<Doc>> brokenArgumentExpressionRenderer;

    private final BreakableArgumentExpressionPrinter breakableArguments;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments;

    private final BiFunction<
        String,
        NodeList<Expression>,
        Optional<ExpressionLambdaArgumentLayout.Plan>
    > expressionLambdaArgumentPlan;

    private final Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer;

    private final TextBlockArgumentSourceLayout textBlockArguments;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> continuationStatementWidth;

    private final ToIntFunction<String> blockStatementWidth;

    private final LayoutDecisionLog layoutDecisions;

    MethodCallPrinter(
            JavaFormatContext context,
            TypePrinter types,
            Function<Expression, Doc> expressionRenderer,
            BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableMethodChainBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments,
            BiFunction<
                String,
                NodeList<Expression>,
                Optional<ExpressionLambdaArgumentLayout.Plan>
            > expressionLambdaArgumentPlan,
            Function<LambdaExpr, String> lambdaParameters,
            Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer,
            Function<Expression, Optional<Doc>> brokenArgumentExpressionRenderer,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.sourceShape = context.sourceShape;
        this.options = context.options;
        this.layoutWidth = context.layoutWidth;
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
            huggableMethodChainBlockLambdaArguments,
            huggableBlockLambdaFirstLine,
            commentedExpressionLambdaArgument,
            huggableExpressionLambdaArguments,
            expressionLambdaArgumentPlan,
            lambdaParameters,
            currentIndentedWidth,
            continuationStatementWidth,
            blockStatementWidth
        );
        this.expressionRenderer = expressionRenderer;
        this.brokenEnclosedForSuffix = brokenEnclosedForSuffix;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.brokenArgumentExpressionRenderer = brokenArgumentExpressionRenderer;
        this.breakableArguments = new BreakableArgumentExpressionPrinter(
            context.sourceShape,
            context.options,
            expressionRenderer,
            brokenArgumentExpressionRenderer,
            compactSource::compact,
            continuationStatementWidth
        );
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.expressionLambdaArgumentPlan = expressionLambdaArgumentPlan;
        this.unformattedTextBlockRenderer = unformattedTextBlockRenderer;
        this.textBlockArguments = new TextBlockArgumentSourceLayout(
            context.sourceText,
            context.options,
            unformattedTextBlockRenderer
        );
        this.currentIndentedWidth = currentIndentedWidth;
        this.continuationStatementWidth = continuationStatementWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.layoutDecisions = context.layoutDecisions;
    }

    Doc methodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallBreakMode.AUTO);
    }

    Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallBreakMode.FORCED);
    }

    Doc methodCallWithTail(MethodCallExpr expression, ExpressionTail tail) {
        return methodCallWithTail(expression, tail, LayoutWidth.LineBudget.CURRENT);
    }

    Doc methodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        MethodCallBreakMode breakMode = methodCallWithTailOverflows(expression, tail, lineBudget)
            ? MethodCallBreakMode.FORCED
            : MethodCallBreakMode.AUTO;
        return methodCallWithTail(expression, tail, breakMode, lineBudget);
    }

    Doc forcedMethodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodCallWithTail(expression, tail, MethodCallBreakMode.FORCED, lineBudget);
    }

    Doc brokenMethodCallWithClosingLine(MethodCallExpr expression, String closingLine) {
        String prefix = methodCallPrefix(expression);
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(closingLine)
        );
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
        if (
            expression.getScope().isEmpty()
            && expression.getNameAsString().equals("yield")
            && !expression.getArguments().isEmpty()
        ) {
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
                call
            );
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
        Optional<Doc> huggableExpressionLambda = huggableExpressionLambdaArguments.apply(
            prefix,
            expression.getArguments()
        );
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
        Optional<Doc> singleObjectCreationArgument = singleObjectCreationArgument(prefix, expression);
        if (singleObjectCreationArgument.isPresent()) {
            return singleObjectCreationArgument.orElseThrow();
        }
        Optional<Doc> singleMethodCallArgument = singleMethodCallArgument(prefix, expression);
        if (singleMethodCallArgument.isPresent()) {
            return singleMethodCallArgument.orElseThrow();
        }
        Optional<Doc> singleBinaryArgument = singleBinaryArgument(prefix, expression.getArguments(), breakMode);
        if (singleBinaryArgument.isPresent()) {
            return singleBinaryArgument.orElseThrow();
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(
            prefix,
            expression,
            expression.getArguments()
        );
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        Doc call = Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    methodCallLine(breakMode),
                    methodCallArgumentList(prefix, expression.getArguments(), Doc.LINE)
                )
            ),
            methodCallLine(breakMode),
            Doc.text(")")
        );
        if (breakMode.isForced()) {
            recordArgumentListWidthBreak(expression, prefix);
            return call;
        }
        return Doc.group(call);
    }

    private Doc methodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            MethodCallBreakMode breakMode,
            LayoutWidth.LineBudget lineBudget
    ) {
        if (tail.isEmpty()) {
            return methodCall(expression, breakMode);
        }
        Optional<Doc> chain = methodCallChain(expression, breakMode, tail.text(), lineBudget);
        if (chain.isPresent()) {
            return chain.orElseThrow();
        }
        if (finalTrailingLineComments(expression).isEmpty()) {
            Optional<Doc> unsuffixedChain = methodCallChain(expression, breakMode, "", lineBudget);
            if (unsuffixedChain.isPresent()) {
                return tail.appendTo(unsuffixedChain.orElseThrow());
            }
        }
        return appendTailBeforeFinalTrailingLineComment(methodCall(expression, breakMode), expression, tail);
    }

    private boolean methodCallWithTailOverflows(
            MethodCallExpr expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        return layoutWidth.line(lineBudget, compactSource.compact(expression) + tail.text()) > options.lineWidth();
    }

    private Doc appendTailBeforeFinalTrailingLineComment(
            Doc call,
            MethodCallExpr expression,
            ExpressionTail tail
    ) {
        Doc trailingComment = finalTrailingLineComment(expression);
        if (trailingComment == Doc.EMPTY) {
            return tail.appendTo(call);
        }
        return Doc.concat(call, tail.doc(), Doc.text(" "), trailingComment);
    }

    private Doc finalTrailingLineComment(MethodCallExpr expression) {
        List<Doc> sourceComments = finalTrailingLineComments(expression)
                .stream()
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

    /**
     * Records the argument list's flat-width decision when a forced call breaks because its single-line form overflowed
     * the budget, so explain can report the real arithmetic instead of an opaque forced break.
     *
     * <p>The auto path is left to the renderer, which width-fits its {@link Doc.Group} and is already explained by the
     * renderer trace. Only the forced path, where the caller already measured an overflow and the printer emits hard
     * breaks, needs the printer to record its own measurement. Recording an argument list whose compact form would
     * still fit is skipped, since such a forced break is not a width decision. This runs after the broken shape is
     * built and does not change it.
     */
    private void recordArgumentListWidthBreak(MethodCallExpr expression, String prefix) {
        String compactArguments = compactSource.compactJoin(expression.getArguments());
        String compactCall = prefix + "(" + compactArguments + ")";
        int flatWidth = currentIndentedWidth.applyAsInt(compactCall);
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        layoutDecisions.recordWidthBreak(
            "argument list",
            "java.expression:" + expression.getClass().getSimpleName(),
            prefix + "(…)",
            flatWidth,
            options.lineWidth(),
            expression.getArguments().size()
        );
    }

    private Optional<Doc> brokenExpressionLambdaArgumentsForOverflow(String prefix, MethodCallExpr expression) {
        Optional<ExpressionLambdaArgumentLayout.Plan> plan = expressionLambdaArgumentPlan.apply(
            prefix,
            expression.getArguments()
        );
        if (plan.filter(argument -> expressionLambdaBodyOpenerOverflows(expression, argument)).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compactSource.compact(scope) + ".").orElse("")
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    Doc methodCallWithoutScope(MethodCallExpr expression) {
        String prefix =
            expression.getTypeArguments()
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
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(
            prefix,
            expression,
            expression.getArguments()
        );
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        return Doc.group(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.SOFT_LINE,
                        methodCallArgumentList(prefix, expression.getArguments(), Doc.LINE)
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
    }

    Optional<Doc> suffixedEnclosedMethodCall(MethodCallExpr expression, boolean leadingBreak) {
        return expression.getScope()
                .filter(EnclosedExpr.class::isInstance)
                .map(EnclosedExpr.class::cast)
                .filter(scope -> leadingBreak
                        || blockStatementWidth.applyAsInt(compactSource.compact(expression) + ";") > options.lineWidth()
                )
                .map(scope -> Doc.concat(
                        brokenEnclosedForSuffix.apply(scope, leadingBreak),
                        Doc.text("."),
                        methodCallWithoutScope(expression)
                ));
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodChains.methodCallChain(expression);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return methodChains.forcedMethodCallChain(expression);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodChains.forcedMethodCallChain(expression, lineBudget);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodChains.forcedMethodCallChain(expression, firstLineWidth);
    }

    Optional<Doc> packedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodChains.packedMethodCallChain(expression, firstLineWidth);
    }

    Optional<String> compactMethodCallChainRoot(MethodCallExpr expression) {
        return methodChains.compactMethodCallChainRoot(expression);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        return methodChains.compactRootWithBrokenFinalChainSegment(expression);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodChains.compactRootWithBrokenFinalChainSegment(expression, lineBudget);
    }

    Optional<Doc> sourceMultilineMethodCallStatement(
            MethodCallExpr expression,
            ExpressionStmt statement
    ) {
        return methodChains.sourceMultilineMethodCallStatement(expression, statement);
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        return methodChains.methodCallChain(expression, force);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix
    ) {
        return methodChains.methodCallChain(expression, breakMode, finalSegmentSuffix);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodChains.methodCallChain(expression, breakMode, finalSegmentSuffix, lineBudget);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodChains.methodCallChain(
            expression,
            breakMode,
            finalSegmentSuffix,
            lineBudget,
            firstLineWidth
        );
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

    boolean hasSourceMultilineExpressionLambdaBody(MethodCallExpr expression) {
        return SourceMultilineLambdaCallLayout.hasMultilineExpressionLambdaMethodCallBody(expression, sourceShape);
    }

    MethodCallChainSourcePlanner.InitializerChainShape methodCallChainInitializerShape(MethodCallExpr expression) {
        return methodChains.methodCallChainInitializerShape(expression);
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
        Doc firstArgumentComment = comments.ownComment(
            expression,
            comment -> comment instanceof LineComment
                    && CommentIndex.startsOnBeginLine(comment, expression)
                    && CommentIndex.startsBeforeEnd(comment, expression)
        );
        if (firstArgumentComment != Doc.EMPTY) {
            argumentComments.add(firstArgumentComment);
        }
        expression.getScope()
                .map(scope -> comments.ownComment(
                        scope,
                        comment -> comment instanceof LineComment
                                && CommentIndex.startsOnBeginLine(comment, expression)
                                && CommentIndex.startsBeforeEnd(comment, expression)
                ))
                .filter(comment -> comment != Doc.EMPTY)
                .ifPresent(argumentComments::add);
        argumentComments.addAll(comments.orphanCommentStatements(expression));
        if (argumentComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentComments))),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Keeps a single text block visually isolated from the call prefix and closing parenthesis.
     *
     * <p>Text blocks already own their internal indentation, so grouping them like ordinary arguments makes trailing
     * comments and the closing parenthesis harder to place predictably.
     */
    private Optional<Doc> singleTextBlockArgument(String prefix, MethodCallExpr expression) {
        if (
            expression.getArguments().size() != 1
            || !(expression.getArguments().get(0) instanceof TextBlockLiteralExpr textBlockLiteralExpr)
        ) {
            return Optional.empty();
        }
        Doc argument = textBlockArguments.methodCallIsExpressionLambdaBody(expression)
            ? textBlockArguments.expressionLambdaSourceMultilineArgument(textBlockLiteralExpr)
            : Doc.indent(Doc.concat(Doc.HARD_LINE, textBlockArgument(textBlockLiteralExpr, expression)));
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                argument,
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    Optional<Doc> sourceMultilineArguments(MethodCallExpr expression) {
        if (
            expression.getArguments().isEmpty()
            || !expression.getAllContainedComments().isEmpty()
            || hasSingleAttachableObjectCreationArgument(expression)
            || hasSingleAttachableMethodCallArgument(expression)
            || (hasHuggableExpressionLambdaArgument(expression)
                && sourceShape.expressionLambdaStartsOnSelectorLine(expression))
            || !sourceShape.methodCallArgumentsSpanMultipleLines(expression)
        ) {
            return Optional.empty();
        }
        Optional<Doc> scopedPrefix = sourceMultilineArgumentScopedPrefix(expression);
        if (scopedPrefix.isPresent()) {
            String prefix = methodCallPrefix(expression);
            return Optional.of(
                Doc.concat(
                    scopedPrefix.orElseThrow(),
                    sourceMultilineArgumentBlock(expression, prefix),
                    Doc.HARD_LINE,
                    Doc.text(")")
                )
            );
        }
        String prefix = methodCallPrefix(expression);
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                sourceMultilineArgumentBlock(expression, prefix),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private Doc sourceMultilineArgumentBlock(MethodCallExpr expression, String prefix) {
        Doc arguments = Doc.concat(
            Doc.HARD_LINE,
            methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
        );
        if (singleTextBlockInsideExpressionLambda(expression)) {
            return textBlockArguments.expressionLambdaSourceMultilineArgument(
                (TextBlockLiteralExpr) expression.getArgument(0)
            );
        }
        return Doc.indent(arguments);
    }

    private Optional<Doc> singleObjectCreationArgument(String prefix, MethodCallExpr expression) {
        if (!hasSingleAttachableObjectCreationArgument(expression)) {
            return Optional.empty();
        }
        ObjectCreationExpr argument = (ObjectCreationExpr) expression.getArgument(0);
        String objectPrefix = compactSource.compact(argument).split("\\(", 2)[0];
        if (currentIndentedWidth.applyAsInt(prefix + "(" + objectPrefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(prefix + "("), objectCreationWithSuffix.apply(argument, ")")));
    }

    private Optional<Doc> singleMethodCallArgument(String prefix, MethodCallExpr expression) {
        if (!hasSingleAttachableMethodCallArgument(expression)) {
            return Optional.empty();
        }
        MethodCallExpr argument = (MethodCallExpr) expression.getArgument(0);
        String argumentPrefix = methodCallPrefix(argument);
        if (currentIndentedWidth.applyAsInt(prefix + "(" + argumentPrefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
            Doc.text(prefix + "("),
            methodCallWithTail(argument, ExpressionTail.of(")"))
        ));
    }

    private boolean hasSingleAttachableObjectCreationArgument(MethodCallExpr expression) {
        return expression.getArguments().size() == 1
            && expression.getArgument(0) instanceof ObjectCreationExpr objectCreation
            && sourceShape.objectCreationArgumentsSpanMultipleLines(objectCreation)
            && sourceShape.startsOnSameLine(expression.getName(), objectCreation)
            && objectCreation.getAnonymousClassBody().isEmpty()
            && objectCreation.getAllContainedComments().isEmpty();
    }

    private boolean hasSingleAttachableMethodCallArgument(MethodCallExpr expression) {
        return expression.getArguments().size() == 1
            && expression.getArgument(0) instanceof MethodCallExpr methodCall
            && sourceShape.methodCallArgumentsSpanMultipleLines(methodCall)
            && sourceShape.startsOnSameLine(expression.getName(), methodCall)
            && methodCall.getAllContainedComments().isEmpty();
    }

    private boolean singleTextBlockInsideExpressionLambda(MethodCallExpr expression) {
        return expression.getArguments().size() == 1
            && expression.getArgument(0) instanceof TextBlockLiteralExpr
            && textBlockArguments.methodCallIsExpressionLambdaBody(expression);
    }

    Optional<Doc> sourceMultilineExpressionLambda(MethodCallExpr expression) {
        if (
            expression.getArguments().isEmpty()
            || !expression.getAllContainedComments().isEmpty()
            || sourceMultilineChainWithConditionalLambda(expression)
            || sourceMultilineMethodCallScope(expression)
            || !sourceShape.expressionLambdaStartsOnSelectorLine(expression)
            || !expressionLambdaSpansMultipleLines(expression)
        ) {
            return Optional.empty();
        }
        String prefix = methodCallPrefix(expression);
        Optional<ExpressionLambdaArgumentLayout.Plan> plan = expressionLambdaArgumentPlan.apply(
            prefix,
            expression.getArguments()
        );
        if (
            plan.filter(argument -> argument.firstLineFits(
                    line -> methodCallRootLineWidth(expression, line),
                    options.lineWidth()
            )).isEmpty()
            || plan.filter(argument -> expressionLambdaBodyOpenerOverflows(expression, argument)).isPresent()
        ) {
            return Optional.empty();
        }
        return huggableExpressionLambdaArguments.apply(prefix, expression.getArguments());
    }

    private boolean sourceMultilineMethodCallScope(MethodCallExpr expression) {
        return methodCallChainIsSourceMultiline(
            expression
        ) && expression.getScope().filter(MethodCallExpr.class::isInstance).isPresent();
    }

    private boolean sourceMultilineChainWithConditionalLambda(MethodCallExpr expression) {
        return (
            methodCallChainIsSourceMultiline(expression)
            && expression.getArguments()
                    .stream()
                    .filter(LambdaExpr.class::isInstance)
                    .map(LambdaExpr.class::cast)
                    .flatMap(lambda -> lambda.getExpressionBody().stream())
                    .anyMatch(ConditionalExpr.class::isInstance)
        );
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            MethodCallExpr expression,
            ExpressionLambdaArgumentLayout.Plan argument
    ) {
        return !argument.bodyFirstSourceLineFits()
            && argument.bodyOpenerFitsOnContinuation(continuationStatementWidth, options.lineWidth())
            && argument.bodyOpenerOverflows(line -> methodCallRootLineWidth(expression, line), options.lineWidth());
    }

    private int methodCallRootLineWidth(MethodCallExpr expression, String firstLine) {
        return expression.getRange()
                .map(range -> Math.max(0, range.begin.column + 1) + firstLine.length())
                .orElseGet(() -> currentIndentedWidth.applyAsInt(firstLine));
    }

    private boolean hasHuggableExpressionLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getExpressionBody().filter(this::huggableExpressionLambdaBody).isPresent()
                );
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
        return expression.getArguments()
                .stream()
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
                        Doc.text("." + methodCallSelector(expression) + "(")
                ));
    }

    private String methodCallSelector(MethodCallExpr expression) {
        return (
            expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString()
        );
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
            docs.add(methodCallArgumentDoc(
                argument,
                last ? "" : ",",
                last && line == Doc.HARD_LINE,
                line == Doc.HARD_LINE
            ));
            if (!last) {
                docs.add(argumentConsumesSuffix(argument) ? line : Doc.concat(Doc.text(","), line));
            }
        }
        return Doc.concat(docs);
    }

    Doc methodCallArgumentList(String prefix, NodeList<Expression> arguments, Doc line) {
        return methodCallArgumentList(arguments, line);
    }

    private boolean argumentConsumesSuffix(Expression argument) {
        return argument instanceof ObjectCreationExpr || argument instanceof MethodCallExpr;
    }

    /**
     * Keeps expressions with their own broken form breakable inside a method-call argument list.
     *
     * <p>The ordinary expression renderer owns the flat spelling. Once the surrounding call list breaks, the expression
     * helper owns the continuation lines so breakable arguments do not collapse back onto an over-wide argument line.
     */
    private Doc methodCallArgumentDoc(Expression argument) {
        return methodCallArgumentDoc(argument, "", false, false);
    }

    private Doc methodCallArgumentDoc(Expression argument, String suffix) {
        return methodCallArgumentDoc(argument, suffix, false, false);
    }

    private Doc methodCallArgumentDoc(
            Expression argument,
            String suffix,
            boolean allowSuffixlessTrailingComment,
            boolean sourceMultilineList
    ) {
        if (
            argument instanceof MethodCallExpr methodCall
            && (!suffix.isEmpty()
                || (allowSuffixlessTrailingComment && !methodCallArgumentTrailingLineComments(methodCall).isEmpty()))
        ) {
            Optional<Doc> textBlockChain = textBlockRootChainArgumentWithSuffix(methodCall, suffix);
            if (textBlockChain.isPresent()) {
                return textBlockChain.orElseThrow();
            }
            Optional<Doc> compact = compactMethodCallArgumentWithSuffix(methodCall, suffix);
            if (compact.isPresent()) {
                return compact.orElseThrow();
            }
        }
        if (argument instanceof MethodCallExpr methodCall && !suffix.isEmpty()) {
            Optional<Doc> chain = methodCallChain(methodCall, MethodCallBreakMode.FORCED, suffix);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
            return methodCallWithTail(methodCall, ExpressionTail.of(suffix));
        }
        if (argument instanceof ObjectCreationExpr objectCreation && !suffix.isEmpty()) {
            return objectCreationWithSuffix.apply(objectCreation, suffix);
        }
        if (argument instanceof MethodCallExpr methodCall && !methodCall.getAllContainedComments().isEmpty()) {
            Optional<Doc> chain = methodCallChain(methodCall, MethodCallBreakMode.FORCED, suffix);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        if (sourceMultilineList) {
            return breakableArguments.sourceMultilineArgument(argument, suffix);
        }
        return breakableArguments.argument(argument, suffix);
    }

    private Optional<Doc> textBlockRootChainArgumentWithSuffix(MethodCallExpr expression, String suffix) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression cursor = expression;
        while (cursor instanceof MethodCallExpr call) {
            if (call.getScope().isEmpty() || sourceShape.methodCallArgumentsSpanMultipleLines(call)) {
                return Optional.empty();
            }
            calls.add(0, call);
            cursor = call.getScope().orElseThrow();
        }
        if (calls.size() < 2 || !(cursor instanceof TextBlockLiteralExpr textBlockLiteralExpr)) {
            return Optional.empty();
        }
        StringBuilder tail = new StringBuilder();
        for (MethodCallExpr call : calls) {
            tail.append(".")
                    .append(methodCallSelector(call))
                    .append("(")
                    .append(compactSource.compactJoin(call.getArguments()))
                    .append(")");
        }
        String literal = unformattedTextBlockRenderer.apply(textBlockLiteralExpr);
        String closingLine = literal.substring(literal.lastIndexOf('\n') + 1) + tail + suffix;
        if (closingLine.length() > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(literal), Doc.text(tail + suffix)));
    }

    private Optional<Doc> compactMethodCallArgumentWithSuffix(MethodCallExpr expression, String suffix) {
        List<JavaCommentTrivia> trailingComments = methodCallArgumentTrailingLineComments(expression);
        if (trailingComments.isEmpty() && sourceShape.spansMultipleLines(expression)) {
            return Optional.empty();
        }
        if (!trailingComments.isEmpty() && hasNonTrailingContainedComments(expression, trailingComments)) {
            return Optional.empty();
        }
        String code =
            (trailingComments.isEmpty() ? compactSource.compact(expression) : compactSource.commentFree(expression))
            + suffix;
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
            List<JavaCommentTrivia> trailingComments
    ) {
        return expression.getAllContainedComments()
                .stream()
                .anyMatch(comment -> trailingComments.stream().noneMatch(trailing -> trailing.comment() == comment));
    }

    private List<JavaCommentTrivia> methodCallArgumentTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsOnEndLine(expression) || comment.startsAfterNodeOnSameLine(expression))
                .filter(comment -> sourceComments.stream().noneMatch(
                        existing -> existing.comment() == comment.comment()
                ))
                .forEach(sourceComments::add);
        int endLine = CommentIndex.endLine(expression, Integer.MIN_VALUE);
        expression.getAllContainedComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .map(JavaCommentTrivia::from)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) == endLine)
                .filter(comment -> sourceComments.stream().noneMatch(
                        existing -> existing.comment() == comment.comment()
                ))
                .forEach(sourceComments::add);
        return sourceComments;
    }

    private Doc textBlockSameLineTrailingComment(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        return expression.getOrphanComments()
                .stream()
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
            MethodCallBreakMode breakMode
    ) {
        if (arguments.size() != 1 || !(arguments.get(0) instanceof BinaryExpr binaryExpr)) {
            return Optional.empty();
        }
        if (
            !breakMode.isForced()
            && currentIndentedWidth.applyAsInt(
                prefix + "(" + compactSource.compact(binaryExpr) + ")"
            ) <= options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        brokenArgumentExpressionRenderer.apply(binaryExpr)
                                .orElseGet(() -> expressionRenderer.apply(binaryExpr))
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    Optional<Doc> assignmentWithBrokenMethodCallArguments(AssignExpr assignExpr, MethodCallExpr methodCall) {
        return assignmentWithBrokenMethodCallArguments(assignExpr, methodCall, "");
    }

    Optional<Doc> assignmentWithBrokenMethodCallArgumentsAndSemicolon(
            AssignExpr assignExpr,
            MethodCallExpr methodCall
    ) {
        return assignmentWithBrokenMethodCallArguments(assignExpr, methodCall, ";");
    }

    private Optional<Doc> assignmentWithBrokenMethodCallArguments(
            AssignExpr assignExpr,
            MethodCallExpr methodCall,
            String finalSegmentSuffix
    ) {
        if (methodCall.getArguments().isEmpty() || !methodCall.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (methodCallChainIsSourceMultiline(methodCall)) {
            String assignmentPrefix = compactSource.compact(assignExpr.getTarget())
                + " "
                + assignExpr.getOperator().asString()
                + " ";
            Optional<Doc> chain = methodCallChain(
                methodCall,
                MethodCallBreakMode.FORCED,
                finalSegmentSuffix,
                LayoutWidth.LineBudget.BLOCK,
                text -> blockStatementWidth.applyAsInt(assignmentPrefix + text)
            );
            if (chain.isPresent()) {
                return Optional.of(
                    Doc.concat(
                        expressionRenderer.apply(assignExpr.getTarget()),
                        Doc.text(" " + assignExpr.getOperator().asString() + " "),
                        chain.orElseThrow()
                    )
                );
            }
        }
        String firstLine = compactSource.compact(assignExpr.getTarget())
            + " "
            + assignExpr.getOperator().asString()
            + " "
            + methodCallPrefix(methodCall)
            + "(";
        if (blockStatementWidth.applyAsInt(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                expressionRenderer.apply(assignExpr.getTarget()),
                Doc.text(" " + assignExpr.getOperator().asString() + " "),
                brokenMethodCall(methodCall),
                Doc.text(finalSegmentSuffix)
            )
        );
    }

    boolean shouldPrintScopeAsDoc(Expression expression) {
        return expression instanceof ArrayCreationExpr
            || expression instanceof ArrayAccessExpr
            || expression instanceof TextBlockLiteralExpr
            || (expression instanceof EnclosedExpr enclosedExpr && enclosedExpr.getInner() instanceof CastExpr);
    }

    private Doc methodCallLine(MethodCallBreakMode breakMode) {
        return breakMode.argumentLine();
    }
}
