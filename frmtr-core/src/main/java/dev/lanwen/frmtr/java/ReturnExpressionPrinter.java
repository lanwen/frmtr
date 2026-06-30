package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders return-statement expressions after statement dispatch has already selected {@code return value;} syntax.
 *
 * <p>This helper owns the return-specific expression decision tree: the whole-return-line width gate, forced method-call
 * chains, forced conditional breaks, and parenthesized continuations for logical complements, enclosed expressions, and
 * binary expressions. The boundary exists because these choices depend on the surrounding {@code return} keyword and
 * semicolon, but the return statement itself still belongs to {@link StatementPrinter}.
 *
 * <p>{@link JavaPrinter} and the existing expression helpers still own broad expression dispatch, compact source text,
 * method-call chain layout, conditional layout, parenthesized expression breaks, and width calculations. This helper
 * keeps only the return-context branch order and receives every reusable formatting decision as a callback.
 */
final class ReturnExpressionPrinter {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Expression, Doc> expression;

    private final ExpressionTailRenderer expressionWithTail;

    private final Function<LambdaExpr, Doc> brokenLambdaExpression;

    private final Function<Expression, String> compact;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> continuationStatementWidth;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall;

    private final BiFunction<
        MethodCallExpr,
        LayoutWidth.LineBudget,
        Optional<Doc>
    > compactRootWithBrokenFinalChainSegment;

    private final BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> forcedMethodCallChain;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChainWithFirstLine;

    private final Function<MethodCallExpr, Doc> brokenMethodCall;

    private final BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;

    private final Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment;

    private final Predicate<MethodCallExpr> sourceMultilineExpressionLambdaBody;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreation;

    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;

    private final BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression;

    private final BiFunction<Expression, Boolean, Doc> binaryLines;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    private final BiFunction<Node, Expression, List<Doc>> trailingValueCommentsBeforeSemicolon;

    private final BiFunction<Node, Expression, Boolean> trailingValueCommentsAreAllBlock;

    private final BiFunction<Node, Expression, Integer> trailingValueBlockCommentInlineWidth;

    private final Predicate<BinaryExpr> binaryHasLineComments;

    private final Function<BinaryExpr, Doc> binaryLinesWithComments;

    private final Function<BinaryExpr, Optional<Doc>> binaryFlatLineWithComments;

    private final ToIntFunction<BinaryExpr> binaryFlatLineWithCommentsWidth;

    private final ReturnBinaryExpressionLayout binaryReturns;

    ReturnExpressionPrinter(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            ObjectCreationLayoutPolicy objectCreationLayoutPolicy,
            SourceShapePolicy sourceShapePolicy,
            Function<Expression, Doc> expression,
            ExpressionTailRenderer expressionWithTail,
            Function<LambdaExpr, Doc> brokenLambdaExpression,
            Function<Expression, String> compact,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth,
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda,
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall,
            BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> compactRootWithBrokenFinalChainSegment,
            BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> forcedMethodCallChain,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChainWithFirstLine,
            Function<MethodCallExpr, Doc> brokenMethodCall,
            BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine,
            Function<MethodCallExpr, String> methodCallPrefix,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
            Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment,
            Predicate<MethodCallExpr> sourceMultilineExpressionLambdaBody,
            Function<ObjectCreationExpr, Doc> brokenObjectCreation,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression,
            BiFunction<Expression, Boolean, Doc> binaryLines,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak,
            BiFunction<Node, Expression, List<Doc>> trailingValueCommentsBeforeSemicolon,
            BiFunction<Node, Expression, Boolean> trailingValueCommentsAreAllBlock,
            BiFunction<Node, Expression, Integer> trailingValueBlockCommentInlineWidth,
            Predicate<BinaryExpr> binaryHasLineComments,
            Function<BinaryExpr, Doc> binaryLinesWithComments,
            Function<BinaryExpr, Optional<Doc>> binaryFlatLineWithComments,
            ToIntFunction<BinaryExpr> binaryFlatLineWithCommentsWidth
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.sourceShapePolicy = sourceShapePolicy;
        this.expression = expression;
        this.expressionWithTail = expressionWithTail;
        this.brokenLambdaExpression = brokenLambdaExpression;
        this.compact = compact;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compact);
        this.currentIndentedWidth = currentIndentedWidth;
        this.continuationStatementWidth = continuationStatementWidth;
        this.sourceMultilineExpressionLambda = sourceMultilineExpressionLambda;
        this.sourceMultilineMethodCall = sourceMultilineMethodCall;
        this.compactRootWithBrokenFinalChainSegment = compactRootWithBrokenFinalChainSegment;
        this.forcedMethodCallChain = forcedMethodCallChain;
        this.forcedMethodCallChainWithFirstLine = forcedMethodCallChainWithFirstLine;
        this.brokenMethodCall = brokenMethodCall;
        this.brokenMethodCallWithClosingLine = brokenMethodCallWithClosingLine;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
        this.methodCallChainHasFinalTrailingLineComment = methodCallChainHasFinalTrailingLineComment;
        this.sourceMultilineExpressionLambdaBody = sourceMultilineExpressionLambdaBody;
        this.brokenObjectCreation = brokenObjectCreation;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.conditionalExpression = conditionalExpression;
        this.binaryLines = binaryLines;
        this.parenthesizedBreak = parenthesizedBreak;
        this.trailingValueCommentsBeforeSemicolon = trailingValueCommentsBeforeSemicolon;
        this.trailingValueCommentsAreAllBlock = trailingValueCommentsAreAllBlock;
        this.trailingValueBlockCommentInlineWidth = trailingValueBlockCommentInlineWidth;
        this.binaryHasLineComments = binaryHasLineComments;
        this.binaryLinesWithComments = binaryLinesWithComments;
        this.binaryFlatLineWithComments = binaryFlatLineWithComments;
        this.binaryFlatLineWithCommentsWidth = binaryFlatLineWithCommentsWidth;
        this.binaryReturns = new ReturnBinaryExpressionLayout(
            options,
            layoutWidth,
            sourceShapePolicy,
            expression,
            compact,
            continuationStatementWidth,
            binaryLines,
            brokenMethodCallWithClosingLine,
            methodCallPrefix
        );
    }

    Doc returnStatement(Expression expression, LayoutWidth.LineBudget lineBudget) {
        if (expression instanceof ObjectCreationExpr objectCreation) {
            if (
                returnLineOverflows(objectCreation, lineBudget)
                || objectCreationLayoutPolicy.shouldPreserveReturnSourceMultilineArguments(objectCreation)
            ) {
                return Doc.concat(Doc.text("return "), brokenObjectCreation.apply(objectCreation), Doc.text(";"));
            }
            return Doc.concat(Doc.text("return "), objectCreationWithSuffix.apply(objectCreation, ";"));
        }
        if (
            expression instanceof MethodCallExpr methodCall
            && methodCallChainHasFinalTrailingLineComment.test(methodCall)
        ) {
            return Doc.concat(
                Doc.text("return "),
                expressionWithTail.render(methodCall, ExpressionTail.SEMICOLON, lineBudget)
            );
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryHasLineComments.test(binaryExpr)) {
            return commentBearingBinaryReturn(binaryExpr, lineBudget);
        }
        Doc preSemicolonComment = preSemicolonValueComment(expression);
        Doc semicolon = preSemicolonComment == Doc.EMPTY
            ? Doc.text(";")
            : Doc.concat(Doc.HARD_LINE, Doc.text(";"));
        return Doc.concat(
            Doc.text("return "),
            returnExpression(expression, lineBudget),
            preSemicolonComment,
            semicolon
        );
    }

    /**
     * Renders a {@code return} whose binary value carries comments between (or trailing) its operands, keeping every
     * comment inline beside its operand instead of detaching it.
     *
     * <p>This owns the comment-bearing binary return as a self-contained unit so it claims the value's comments before the
     * default {@link #preSemicolonValueComment} path can route them onto their own lines. The default path produced two
     * defects for an inline-block-comment chain: it wrapped the whole value in {@code return (\n … \n)} with a dangling
     * {@code ;}, and it detached the final operand's trailing {@code /* ... *}{@code /} onto its own line. Here the value is
     * rendered with the shared comment-aware binary layout (the same {@code binaryLinesWithComments} the {@code if}/
     * assignment callers use) — flat when the whole {@code return value;} line still fits so a short chain such as
     * {@code return a /* x *}{@code / || b /* y *}{@code /;} stays on one line, otherwise one operand per line under the
     * normal binary continuation indent. The final-operand trailing comment, which JavaParser parks as an orphan of the
     * enclosing {@code return} rather than inside the binary, is recovered through the shared
     * {@code trailingInitializerCommentsBeforeSemicolon} bucket and appended inline before the {@code ;} so it reads
     * {@code lastOperand /* note *}{@code /;}.
     */
    private Doc commentBearingBinaryReturn(BinaryExpr binaryExpr, LayoutWidth.LineBudget lineBudget) {
        Node semicolonOwner = binaryExpr.getParentNode().orElse(null);
        // A trailing comment after the final operand is an inline block comment only when source kept it before the ;
        // (errorCode == 599 /* note */;); a // line comment must drop onto its own line above the ; because it runs to
        // end-of-line and would otherwise swallow the ;. Peek the kind without claiming so the chosen terminator decides
        // inline-versus-detached before the rendering query consumes the comment.
        boolean trailingIsInlineBlock = semicolonOwner != null
            && Boolean.TRUE.equals(trailingValueCommentsAreAllBlock.apply(semicolonOwner, binaryExpr));
        // The committed value render claims the binary's between-operand comments, so only build the shape that is
        // actually used: deciding the flat-versus-broken fit before rendering keeps the comment-aware render from claiming
        // a comment in a flat shape we then discard, which would leave the broken render with an empty comment.
        Doc value = commentBearingBinaryReturnFlatLineFits(binaryExpr, lineBudget)
            ? binaryFlatLineWithComments.apply(binaryExpr).orElseGet(
                () -> Doc.indent(binaryLinesWithComments.apply(binaryExpr))
            )
            : Doc.indent(binaryLinesWithComments.apply(binaryExpr));
        if (trailingIsInlineBlock) {
            Doc trailingComment = inlineTrailingComments(
                trailingValueCommentsBeforeSemicolon.apply(semicolonOwner, binaryExpr)
            );
            return Doc.concat(Doc.text("return "), value, trailingComment, Doc.text(";"));
        }
        Doc preSemicolonComment = preSemicolonValueComment(binaryExpr);
        Doc semicolon = preSemicolonComment == Doc.EMPTY
            ? Doc.text(";")
            : Doc.concat(Doc.HARD_LINE, Doc.text(";"));
        return Doc.concat(Doc.text("return "), value, preSemicolonComment, semicolon);
    }

    private Doc inlineTrailingComments(List<Doc> recovered) {
        if (recovered.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(Doc.text(" "), Doc.join(Doc.text(" "), recovered));
    }

    private boolean commentBearingBinaryReturnFlatLineFits(BinaryExpr binaryExpr, LayoutWidth.LineBudget lineBudget) {
        int valueWidth = binaryFlatLineWithCommentsWidth.applyAsInt(binaryExpr);
        if (valueWidth == Integer.MAX_VALUE) {
            return false;
        }
        Node semicolonOwner = binaryExpr.getParentNode().orElse(null);
        // Account for the inline trailing block comment (errorCode == 401 /* note */;) the flat shape appends after the
        // value but before the ;, so a value that fits on its own does not overflow once its trailing comment is added.
        int trailingWidth = semicolonOwner == null
            ? 0
            : trailingValueBlockCommentInlineWidth.apply(semicolonOwner, binaryExpr);
        String line = "return ".concat("x".repeat(valueWidth + trailingWidth)).concat(";");
        return returnLineWidth(binaryExpr, line, lineBudget) <= options.lineWidth();
    }

    /**
     * Recovers the {@code //} line comment that trails a multi-line return value's last operand but begins before the
     * closing {@code ;}, and renders it on its own continuation line so the {@code ;} can drop onto its own line below.
     *
     * <p>This is the {@code return value;} sibling of the field/local-variable initializer recovery
     * ({@code VariableInitializerLayout.preSemicolonInitializerComment}). For a multi-line binary return value such as
     * {@code return a + // a}{@code b + // b}{@code c; // c}, JavaParser parks the final {@code // c} as the last
     * operand's own contained trivia, which begins after the whole value's last token. The binary printer's
     * between-operand recovery only emits comments <em>between</em> operands, and there is no declarator trailing slot to
     * fall back on here, so that comment is otherwise dropped. We claim exactly the line comments that begin after the
     * return value ends and before the {@code ;} (keyed on source-order ownership through the shared
     * {@code trailingInitializerCommentsBeforeSemicolon} query), indent them to the operand-continuation column the
     * broken-binary lines already use, and let the caller drop the {@code ;} onto its own base-indent line below. When
     * there is no such comment the result is {@link Doc#EMPTY}, so the terminator stays byte-identical to the prior
     * {@code concat(value, ";")} for every return that does not carry a trailing pre-{@code ;} comment.
     */
    private Doc preSemicolonValueComment(Expression expression) {
        Node semicolonOwner = expression.getParentNode().orElse(null);
        if (semicolonOwner == null) {
            return Doc.EMPTY;
        }
        List<Doc> recovered = trailingValueCommentsBeforeSemicolon.apply(semicolonOwner, expression);
        if (recovered.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, recovered)));
    }

    /**
     * Prints the return value flat unless the complete {@code return value;} line is too wide.
     *
     * <p>The gate checks the keyword and semicolon with the value because a value that fits by itself can still overflow
     * once it is placed inside a return statement. When the whole statement fits, expression dispatch keeps its ordinary
     * shape; only overflowing return lines enter the return-specific break tree.
     *
     * <p>The break decision for a method-call value is width-first: the flat {@code return value;} width is checked before
     * the source-shape forced breaks. Only when the flat form genuinely overflows do the source-multiline signals
     * ({@code methodCallChainIsSourceMultiline}, {@code wasMultiline}, {@code sourceMultilineExpressionLambdaBody}) act as
     * a fallback that chooses the broken chain shape. This keeps the result a function of width rather than of the input's
     * line shape, so a {@code return <call>} that fits stays flat whether the source wrote it on one line or across
     * several, and the formatter reaches the same fixed point from either input.
     */
    private Doc returnExpression(Expression expression, LayoutWidth.LineBudget lineBudget) {
        Optional<BinaryExpr> sourceMultilineEnclosedBinary = sourceMultilineEnclosedBinary(expression);
        if (sourceMultilineEnclosedBinary.isPresent()) {
            BinaryExpr binaryExpr = sourceMultilineEnclosedBinary.orElseThrow();
            return binaryReturns.directBinaryReturn(binaryExpr, expression, lineBudget).orElseGet(
                () -> parenthesizedBreak.apply(binaryExpr, true)
            );
        }
        if (sourceMultilineObjectCreation(expression)) {
            return brokenObjectCreation.apply((ObjectCreationExpr) expression);
        }
        if (
            expression instanceof ConditionalExpr conditionalExpr
            && conditionalReturnLineOverflows(conditionalExpr, lineBudget)
        ) {
            return conditionalExpression.apply(conditionalExpr, true);
        }
        if (
            expression instanceof MethodCallExpr methodCall
            && objectCreationRootMethodCallReturnLineOverflows(methodCall, lineBudget)
        ) {
            Optional<Doc> forcedChain = returnWithForcedMethodCallChain(methodCall, lineBudget);
            if (forcedChain.isPresent()) {
                return forcedChain.orElseThrow();
            }
        }
        if (returnLineFits(expression, lineBudget)) {
            return this.expression.apply(expression);
        }
        if (
            expression instanceof MethodCallExpr methodCall
            && (methodCallChainIsSourceMultiline.test(methodCall)
                || sourceSpansMultipleLines(methodCall)
                || sourceMultilineExpressionLambdaBody.test(methodCall))
        ) {
            Optional<Doc> forcedChain = returnWithForcedMethodCallChain(methodCall, lineBudget);
            if (forcedChain.isPresent()) {
                return forcedChain.orElseThrow();
            }
        }
        if (
            expression instanceof BinaryExpr binaryExpr
            && binaryReturns.shouldUseExpressionRenderer(binaryExpr)
        ) {
            return this.expression.apply(binaryExpr);
        }
        return brokenReturnExpression(expression, lineBudget).orElseGet(() -> this.expression.apply(expression));
    }

    private boolean objectCreationRootMethodCallReturnLineOverflows(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        Optional<Expression> scope = expression.getScope();
        if (scope.filter(ObjectCreationExpr.class::isInstance).isEmpty()) {
            return false;
        }
        String line = "return " + methodCallPrefix.apply(expression) + "(";
        return returnLineWidth(expression, line, lineBudget) > options.lineWidth();
    }

    private boolean conditionalReturnLineOverflows(ConditionalExpr expression, LayoutWidth.LineBudget lineBudget) {
        String line = "return " + conditionalProjection.line(expression) + ";";
        return returnLineWidth(expression, line, lineBudget) > options.lineWidth();
    }

    private Optional<BinaryExpr> sourceMultilineEnclosedBinary(Expression expression) {
        if (
            expression instanceof EnclosedExpr enclosedExpr
            && enclosedExpr.getInner() instanceof BinaryExpr binaryExpr
            && sourceShapePolicy.wasMultiline(expression)
        ) {
            return Optional.of(binaryExpr);
        }
        return Optional.empty();
    }

    private boolean sourceMultilineObjectCreation(Expression expression) {
        return expression instanceof ObjectCreationExpr objectCreationExpr
            && objectCreationLayoutPolicy.shouldPreserveSourceMultilineArguments(objectCreationExpr);
    }

    private boolean sourceSpansMultipleLines(Expression expression) {
        return sourceShapePolicy.wasMultiline(expression);
    }

    private boolean returnLineFits(Expression expression, LayoutWidth.LineBudget lineBudget) {
        return !returnLineOverflows(expression, lineBudget);
    }

    private boolean returnLineOverflows(Expression expression, LayoutWidth.LineBudget lineBudget) {
        String line = "return " + compact.apply(expression) + ";";
        return returnLineWidth(expression, line, lineBudget) > options.lineWidth();
    }

    /**
     * Measures a candidate {@code return value;} line at the indentation it will actually render at, not at the source
     * column the value sat in.
     *
     * <p>The earlier estimate derived the second term from {@code expression.getRange().begin.column}, which is the
     * value's <em>source</em> column. When a {@code return} was co-located after a label prefix
     * ({@code case "x": return obj.getX();}), that column was large, so the estimate overshot 120, the value broke, and a
     * later pass — with the {@code case} and {@code return} now on their own lines and the source column small — saw the
     * estimate drop back under budget and collapsed it. That is the {@code begin.column}-driven break-then-collapse cycle
     * tracked in #137. The return value always renders at a deterministic column: the statement's rendered indentation
     * plus {@code "return "}. Counting the enclosing block/type nesting through {@link LayoutWidth#nodeLine} reproduces
     * that indentation regardless of where the value sat in source, so the fit/break decision is identical on every pass
     * (the same source-column-to-rendered-column correction made for {@code if} conditions in #155 and for hugged call
     * openers in #161). The {@code currentIndentedWidth} floor is kept so a {@code return} nested directly under a member
     * (no enclosing block) is still measured against at least one indentation unit.
     */
    private int returnLineWidth(Expression expression, String line, LayoutWidth.LineBudget lineBudget) {
        int budgetWidth = layoutWidth.line(lineBudget, line);
        int renderedColumnWidth = Math.max(
            layoutWidth.nodeLine(expression, line),
            currentIndentedWidth.applyAsInt(line)
        );
        return Math.max(budgetWidth, renderedColumnWidth);
    }

    /**
     * Tries the width-triggered return branches in the same order as the old inline printer.
     *
     * <p>Method calls and conditionals are tried first because their helpers already know how to force a useful break for
     * the whole expression. Parenthesized-looking values are handled next so the long part moves inside parentheses
     * instead of leaving a wide value directly after {@code return}.
     */
    private Optional<Doc> brokenReturnExpression(Expression expression, LayoutWidth.LineBudget lineBudget) {
        Optional<Doc> methodCallChain = returnWithForcedMethodCallChain(expression, lineBudget);
        if (methodCallChain.isPresent()) {
            return methodCallChain;
        }
        Optional<Doc> conditionalBreak = returnWithForcedConditionalBreak(expression);
        if (conditionalBreak.isPresent()) {
            return conditionalBreak;
        }
        Optional<Doc> lambdaBreak = returnWithForcedLambdaBreak(expression);
        if (lambdaBreak.isPresent()) {
            return lambdaBreak;
        }
        Optional<Doc> logicalComplementBreak = returnWithLogicalComplementBreak(expression);
        if (logicalComplementBreak.isPresent()) {
            return logicalComplementBreak;
        }
        return returnWithParenthesizedValueBreak(expression, lineBudget);
    }

    private Optional<Doc> returnWithForcedMethodCallChain(Expression expression, LayoutWidth.LineBudget lineBudget) {
        if (!(expression instanceof MethodCallExpr methodCall)) {
            return Optional.empty();
        }
        Optional<Doc> expressionLambda = sourceMultilineExpressionLambda.apply(methodCall);
        if (expressionLambda.isPresent()) {
            return expressionLambda;
        }
        if (!methodCallChainIsSourceMultiline.test(methodCall)) {
            Optional<Doc> sourceMultilineCall = sourceMultilineMethodCall.apply(methodCall);
            if (sourceMultilineCall.isPresent()) {
                return sourceMultilineCall;
            }
        }
        if (methodCallChainIsSourceMultiline.test(methodCall)) {
            return forcedMethodCallChainWithFirstLine
                    .apply(methodCall, text -> returnLineWidth(methodCall, "return " + text, lineBudget))
                    .or(() -> forcedMethodCallChain.apply(methodCall, lineBudget));
        }
        if (methodCall.getScope().filter(ObjectCreationExpr.class::isInstance).isPresent()) {
            return forcedMethodCallChainWithFirstLine
                    .apply(methodCall, text -> returnLineWidth(methodCall, "return " + text, lineBudget))
                    .or(() -> forcedMethodCallChain.apply(methodCall, lineBudget));
        }
        return compactRootWithBrokenFinalChainSegment.apply(methodCall, lineBudget)
                .or(() -> forcedMethodCallChainWithFirstLine.apply(
                        methodCall,
                        text -> returnLineWidth(methodCall, "return " + text, lineBudget)
                ))
                .or(() -> forcedMethodCallChain.apply(methodCall, lineBudget))
                .or(() -> Optional.of(brokenMethodCall.apply(methodCall)));
    }

    private Optional<Doc> returnWithForcedConditionalBreak(Expression expression) {
        if (!(expression instanceof ConditionalExpr conditionalExpr)) {
            return Optional.empty();
        }
        return Optional.of(conditionalExpression.apply(conditionalExpr, true));
    }

    private Optional<Doc> returnWithForcedLambdaBreak(Expression expression) {
        if (!(expression instanceof LambdaExpr lambdaExpr) || lambdaExpr.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        if (lambdaExpr.getExpressionBody().filter(MethodCallExpr.class::isInstance).isPresent()) {
            return Optional.of(this.expression.apply(lambdaExpr));
        }
        return Optional.of(brokenLambdaExpression.apply(lambdaExpr));
    }

    /**
     * Keeps {@code !} attached while breaking the enclosed operand inside its existing parentheses.
     *
     * <p>The logical-complement case is separate from ordinary enclosed expressions because the prefix operator should
     * stay visible at the return value start; only the inner parenthesized expression needs the multi-line shape.
     */
    private Optional<Doc> returnWithLogicalComplementBreak(Expression expression) {
        if (
            !(expression instanceof UnaryExpr unaryExpr)
            || unaryExpr.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
            || !(unaryExpr.getExpression() instanceof EnclosedExpr enclosedExpr)
        ) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text("!"), parenthesizedBreak.apply(enclosedExpr.getInner(), false)));
    }

    /**
     * Breaks grouped return values by moving the long expression inside parentheses and direct binary values as
     * continuation lines.
     *
     * <p>Already enclosed expressions keep their source grouping and break only the inner value. Direct binary values use
     * the binary-expression policy directly unless comments inside the binary need the parenthesized shape to keep their
     * ownership obvious.
     */
    private Optional<Doc> returnWithParenthesizedValueBreak(Expression expression, LayoutWidth.LineBudget lineBudget) {
        if (expression instanceof EnclosedExpr enclosedExpr) {
            if (enclosedExpr.getInner() instanceof BinaryExpr binaryExpr) {
                Optional<Doc> directBinary = binaryReturns.directBinaryReturn(binaryExpr, enclosedExpr, lineBudget);
                if (directBinary.isPresent()) {
                    return directBinary;
                }
            }
            return Optional.of(parenthesizedBreak.apply(enclosedExpr.getInner(), false));
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            Optional<Doc> directBinary = binaryReturns.directBinaryReturn(binaryExpr, lineBudget);
            if (directBinary.isPresent()) {
                return directBinary;
            }
            return Optional.of(parenthesizedBreak.apply(binaryExpr, false));
        }
        return Optional.empty();
    }
}
