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

    /**
     * A forced-chain callback that also carries the return value's {@link LayoutContext}. The second parameter is the
     * width shape the chain callee measures at — a fixed-baseline width measure ({@link ToIntFunction}, threaded into
     * both the residual-probe and first-line slots) or a first-line width measure; the third threads the {@code "return "}
     * left-edge prefix so the chain width gates can attribute it at the rendered column. There is no three-argument
     * {@code BiFunction}, so this is its own interface.
     */
    @FunctionalInterface
    interface ChainWithLayout<W> {
        Optional<Doc> apply(MethodCallExpr expression, W widthShape, LayoutContext layout);
    }

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Expression, Doc> expression;

    private final ExpressionTailRenderer expressionWithTail;

    private final Function<LambdaExpr, Doc> brokenLambdaExpression;

    private final Function<Expression, String> compact;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall;

    // LDM-2f (#190): these three chain callbacks reach {@code MethodCallChainPrinter.compactRootLineWidth}, so each takes
    // a {@link LayoutContext} the return caller fills with {@code withLeftEdgePrefix("return ")}. That lets the gate
    // attribute the {@code return } prefix at the rendered column instead of inferring it from the value's source column.
    private final ChainWithLayout<ToIntFunction<String>> compactRootWithBrokenFinalChainSegment;

    // Canonical-fan cutover seam (End-state A): emits the source-neutral one-selector-per-line {@code chainFanOut} for a
    // fan-threshold, comment/lambda-free return chain, independent of the value's source shape. The width shape here is the
    // final-segment suffix ({@code ";"} for a return terminator carried into the fan). The return caller threads
    // {@code withLeftEdgePrefix("return ")} so a promoted factory root measures its opener at the rendered column, exactly
    // like the imperative forced-chain callbacks below.
    private final ChainWithLayout<String> canonicalFanChain;

    private final ChainWithLayout<ToIntFunction<String>> forcedMethodCallChain;

    private final ChainWithLayout<ToIntFunction<String>> forcedMethodCallChainWithFirstLine;

    private final Function<MethodCallExpr, Doc> brokenMethodCall;

    private final BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;

    private final Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment;

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

    private final Predicate<Expression> binaryFansChainOperand;

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
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda,
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall,
            ChainWithLayout<ToIntFunction<String>> compactRootWithBrokenFinalChainSegment,
            ChainWithLayout<String> canonicalFanChain,
            ChainWithLayout<ToIntFunction<String>> forcedMethodCallChain,
            ChainWithLayout<ToIntFunction<String>> forcedMethodCallChainWithFirstLine,
            Function<MethodCallExpr, Doc> brokenMethodCall,
            BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine,
            Function<MethodCallExpr, String> methodCallPrefix,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
            Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment,
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
            ToIntFunction<BinaryExpr> binaryFlatLineWithCommentsWidth,
            Predicate<Expression> binaryFansChainOperand
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.sourceShapePolicy = sourceShapePolicy;
        this.expression = expression;
        this.expressionWithTail = expressionWithTail;
        this.brokenLambdaExpression = brokenLambdaExpression;
        this.compact = compact;
        this.sourceMultilineExpressionLambda = sourceMultilineExpressionLambda;
        this.sourceMultilineMethodCall = sourceMultilineMethodCall;
        this.compactRootWithBrokenFinalChainSegment = compactRootWithBrokenFinalChainSegment;
        this.canonicalFanChain = canonicalFanChain;
        this.forcedMethodCallChain = forcedMethodCallChain;
        this.forcedMethodCallChainWithFirstLine = forcedMethodCallChainWithFirstLine;
        this.brokenMethodCall = brokenMethodCall;
        this.brokenMethodCallWithClosingLine = brokenMethodCallWithClosingLine;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
        this.methodCallChainHasFinalTrailingLineComment = methodCallChainHasFinalTrailingLineComment;
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
        this.binaryFansChainOperand = binaryFansChainOperand;
        this.binaryReturns = new ReturnBinaryExpressionLayout(
            options,
            layoutWidth,
            sourceShapePolicy,
            expression,
            compact,
            binaryLines,
            brokenMethodCallWithClosingLine,
            methodCallPrefix
        );
    }

    Doc returnStatement(Expression expression, LayoutContext layout) {
        if (expression instanceof ObjectCreationExpr objectCreation) {
            if (
                returnLineOverflows(objectCreation, layout)
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
            // The comment-bearing return chain hands its tail renderer the ordinary block baseline. The renderer already
            // measures the chain through its own width gates, and this shape is only reached for a return that owns its
            // own first column (the common block statement), so the fixed two-unit block baseline
            // ({@link LayoutWidth#blockStatement}) reproduces the width the return statement measured at here.
            return Doc.concat(
                Doc.text("return "),
                expressionWithTail.render(methodCall, ExpressionTail.SEMICOLON, layoutWidth::blockStatement)
            );
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryHasLineComments.test(binaryExpr)) {
            return commentBearingBinaryReturn(binaryExpr, layout);
        }
        Doc preSemicolonComment = preSemicolonValueComment(expression);
        if (preSemicolonComment != Doc.EMPTY) {
            // A pre-semicolon line comment forces the terminator onto its own line, so the whole return already breaks.
            // The value's flat-versus-broken choice here is made by the imperative oracle exactly as before (the trailing
            // comment does not enter its width gate), keeping this comment-bearing shape byte-identical.
            return Doc.concat(
                Doc.text("return "),
                returnExpression(expression, layout),
                preSemicolonComment,
                Doc.concat(Doc.HARD_LINE, Doc.text(";"))
            );
        }
        // Source-multiline entries whose broken shape is a source-preserved layout (a source-multiline object creation,
        // or an enclosed binary the direct-binary/parenthesized layout owns) keep the imperative oracle for that
        // broken-shape selection; they pre-empt the renderer-measured gate below. The object-creation-rooted method-call
        // chain routes through the renderer instead: it falls through to the flat-versus-broken conditional group, whose
        // broken arm is the chain's ranked Doc.bestFitting (LDM-3g, #210).
        Optional<Doc> preempted = preemptedReturnValue(expression, layout);
        if (preempted.isPresent()) {
            return Doc.concat(Doc.text("return "), preempted.orElseThrow(), Doc.text(";"));
        }
        if (expression.getComment().isPresent() || !expression.getAllContainedComments().isEmpty()) {
            // A comment-bearing value cannot use the conditional group: both arms build the value, and rendering a value
            // claims its comments (identity-based, first-builder-wins), so the comment would be owned by whichever arm is
            // built first while the renderer independently picks an arm by width. When those disagree the comment is
            // dropped and the two passes diverge. The imperative oracle renders the value exactly once, so comment-bearing
            // returns stay on it (byte-identical to before); only comment-free returns move to the renderer-measured gate.
            return Doc.concat(Doc.text("return "), returnExpression(expression, layout), Doc.text(";"));
        }
        // SPIKE (fan-root-true-column, #190). A binary return whose operand is a fan-threshold chain
        // ({@code return promotesFirstCall(analysis.root()) || analysis.calls().stream()….anyMatch(ref);}) commits a
        // source-neutral operand-per-line shape rather than falling to the flat-versus-broken conditionalGroup below, whose
        // flat arm carries the fanned operand's hard break and can never be chosen. Both the operand-per-line skeleton and
        // the operand fan below it are pure AST functions (link-count rule), so committing this shape unconditionally here
        // reaches it on BOTH passes and is a fixpoint. Comment-bearing binaries are handled above (they never reach here);
        // non-fan binaries fall through to the conditionalGroup byte-for-byte.
        if (expression instanceof BinaryExpr operandFanBinary && binaryFansChainOperand.test(operandFanBinary)) {
            // Binary / logical / string-concat operand break (gjf/prettier-java, comment #1). Render one operand per line —
            // each operator-led operand on its own line, its chain fanning below when the operand overflows — through the
            // source-neutral {@code binaryLines(…, forceBreak=true)}. This is the same operand-per-line convention the
            // {@code if}/{@code while} control-condition path already applies to a logical whose LAST operand is a fanning
            // chain ({@code lines.size() < 3 || lines.stream().skip(1)….anyMatch(...)}); the return path matches it, so
            // {@code || <chainRoot>} leads the second operand on its own line instead of gluing onto the first operand's tail.
            // Both the operand-per-line skeleton and the per-operand fan are pure AST functions (the fan is the
            // width-independent link-count rule), so committing this shape unconditionally — for a fanning operand in ANY
            // position, last included — is a fixpoint reached on both passes.
            return Doc.concat(
                Doc.text("return "),
                Doc.indent(binaryLines.apply(expression, true)),
                Doc.text(";")
            );
        }
        // Judge "does `return value;` fit on one line" at the true rendered column: build the flat return line and the
        // broken return line and let the renderer choose via Doc.conditionalGroup. The earlier gate compared a
        // reconstructed nodeLine/budget width, which could disagree with the column write reaches (the #137/#155
        // width-at-wrong-column family) and print a genuinely over-width return flat, then break it on a later pass. The
        // broken arm keeps the ranked/imperative broken-shape selection (forced chain, forced ternary, lambda break,
        // logical-complement break, parenthesized/binary continuation); the conditional group only moves the
        // flat-versus-broken verdict to the renderer.
        Doc flatReturn = Doc.concat(Doc.text("return "), this.expression.apply(expression), Doc.text(";"));
        Doc brokenReturn = Doc.concat(Doc.text("return "), brokenReturnValue(expression, layout), Doc.text(";"));
        return Doc.conditionalGroup(List.of(flatReturn, brokenReturn));
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
    private Doc commentBearingBinaryReturn(BinaryExpr binaryExpr, LayoutContext layout) {
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
        Doc value = commentBearingBinaryReturnFlatLineFits(binaryExpr, layout)
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

    private boolean commentBearingBinaryReturnFlatLineFits(BinaryExpr binaryExpr, LayoutContext layout) {
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
        return returnLineWidth(binaryExpr, line, layout) <= options.lineWidth();
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
     * Renders the return value on the comment-bearing terminator path, where a pre-semicolon comment already forces the
     * statement to break, so the flat-versus-broken verdict stays on the imperative oracle.
     *
     * <p>This reproduces the historical branch order — the source-shape/width-ranked pre-empt branches, then the
     * {@link #returnLineFits} width gate, then the imperative broken tree — so a return that carries a trailing pre-{@code
     * ;} comment is byte-identical to before. The renderer-measured gate that replaces {@code returnLineFits} lives in
     * {@link #returnStatement}, on the ordinary (no pre-{@code ;} comment) path where the whole {@code return value;} line
     * can be handed to a {@link Doc#conditionalGroup(java.util.List)}.
     */
    private Doc returnExpression(Expression expression, LayoutContext layout) {
        Optional<Doc> preempted = preemptedReturnValue(expression, layout);
        if (preempted.isPresent()) {
            return preempted.orElseThrow();
        }
        if (returnLineFits(expression, layout)) {
            return this.expression.apply(expression);
        }
        return brokenReturnValue(expression, layout);
    }

    /**
     * Renders the return value for the source-multiline branches whose broken shape is a source-preserved layout the
     * binary/object-creation printers own, so they cannot be expressed as a two-arm flat-versus-broken group and keep
     * their imperative oracle.
     *
     * <p>These are the source-multiline enclosed binary (broken through the direct-binary/parenthesized layout) and the
     * source-multiline object creation. Each internally decides flat-versus-broken and, when broken, which broken shape to
     * use; the renderer-measured gate cannot express that until those printers expose their own ranked candidates
     * (layout-decision-model milestone LDM-4, context-as-data → enum), so they pre-empt it. The object-creation-rooted
     * method-call chain falls through to the flat-versus-broken conditional group in {@link #returnStatement}, whose
     * broken arm is the chain's ranked {@link Doc#bestFitting(java.util.List)} (LDM-3g, #210). Everything else falls
     * through to that conditional group too.
     */
    private Optional<Doc> preemptedReturnValue(Expression expression, LayoutContext layout) {
        Optional<BinaryExpr> sourceMultilineEnclosedBinary = sourceMultilineEnclosedBinary(expression);
        if (sourceMultilineEnclosedBinary.isPresent()) {
            BinaryExpr binaryExpr = sourceMultilineEnclosedBinary.orElseThrow();
            // LDM-4 (deferred): the enclosed-binary broken shape is chosen among source-preserved layouts (direct-binary
            // continuation versus parenthesized break) that the binary printer does not yet expose as ranked candidates,
            // so this stays on the imperative oracle rather than the renderer-measured flat-versus-broken group.
            return Optional.of(
                binaryReturns.directBinaryReturn(binaryExpr, expression, layout).orElseGet(
                    () -> parenthesizedBreak.apply(binaryExpr, true)
                )
            );
        }
        if (sourceMultilineObjectCreation(expression)) {
            // LDM-4 (deferred): the source-multiline object creation is a source-preserved shape the object-creation
            // printer does not yet expose as a ranked candidate, so it stays on the imperative oracle here.
            return Optional.of(brokenObjectCreation.apply((ObjectCreationExpr) expression));
        }
        return Optional.empty();
    }

    /**
     * Builds the broken return value used as the {@link Doc#conditionalGroup(java.util.List)} fallback in
     * {@link #returnStatement}: the shape the return takes once the renderer has judged the flat {@code return value;}
     * too wide for the columns left.
     *
     * <p>This keeps the historical ranked/imperative broken-shape selection reached when the old {@code returnLineFits}
     * gate failed: a source-multiline or lambda-bodied method-call value forces its chain shape, a string concatenation
     * around a source-multiline call argument stays on ordinary expression dispatch, and everything else routes through
     * {@link #brokenReturnExpression} (forced chain, forced ternary, lambda break, logical-complement break,
     * parenthesized/binary continuation). The flat arm is ordinary expression dispatch; only the flat-versus-broken
     * verdict moved to the renderer.
     */
    private Doc brokenReturnValue(Expression expression, LayoutContext layout) {
        if (
            expression instanceof MethodCallExpr methodCall
            && methodCallChainIsSourceMultiline.test(methodCall)
        ) {
            Optional<Doc> forcedChain = returnWithForcedMethodCallChain(methodCall, layout);
            if (forcedChain.isPresent()) {
                return forcedChain.orElseThrow();
            }
        }
        return brokenReturnExpression(expression, layout).orElseGet(() -> this.expression.apply(expression));
    }

    private Optional<BinaryExpr> sourceMultilineEnclosedBinary(Expression expression) {
        // A return value that is a redundantly-parenthesized binary ({@code return (a && b);}) is owned by the
        // direct-binary / parenthesized-break layout regardless of the author's line shape: that layout strips the
        // redundant parens when the binary fits ({@code return a && b;}) and preserves any interior line comments through
        // {@code binaryLinesWithComments} when it must break. The width-driven conditional group in
        // {@link #returnStatement} cannot express either — its flat arm renders the {@code EnclosedExpr} verbatim (parens
        // kept) and would drop between-operand comments — so this enclosed-binary shape stays on the imperative oracle,
        // which itself decides flat-versus-broken by width internally ({@code directBinaryReturn}).
        if (
            expression instanceof EnclosedExpr enclosedExpr
            && enclosedExpr.getInner() instanceof BinaryExpr binaryExpr
        ) {
            return Optional.of(binaryExpr);
        }
        return Optional.empty();
    }

    private boolean sourceMultilineObjectCreation(Expression expression) {
        return expression instanceof ObjectCreationExpr objectCreationExpr
            && objectCreationLayoutPolicy.shouldPreserveSourceMultilineArguments(objectCreationExpr);
    }

    private boolean returnLineFits(Expression expression, LayoutContext layout) {
        return !returnLineOverflows(expression, layout);
    }

    private boolean returnLineOverflows(Expression expression, LayoutContext layout) {
        String line = "return " + compact.apply(expression) + ";";
        return returnLineWidth(expression, line, layout) > options.lineWidth();
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
     * openers in #161). The {@link LayoutWidth#currentIndented} floor is kept so a {@code return} nested directly under a
     * member (no enclosing block) is still measured against at least one indentation unit.
     *
     * <p>The transitional fixed-baseline floor ({@code max(baseline, renderedColumn)}) is retired (U2, #190): a
     * {@code return} always renders at least two block/type levels deep, so the rendered-column term already dominates the
     * two-unit block baseline, and the only deeper baseline (a return nested in a block-lambda body under a broken chain,
     * about five units) is not load-bearing here — the return value's own renderer (object creation, binary, chain)
     * re-gates its width, so an optimistic fit verdict is caught downstream. Dropping the floor is byte-identical across
     * the format-fixture suite and the kafka/camel/cayenne/tomcat/zookeeper corpora, and removes a return-path read of the
     * transitional fixed-baseline selector.
     */
    private int returnLineWidth(Expression expression, String line, LayoutContext layout) {
        return Math.max(
            layoutWidth.nodeLine(expression, line),
            layoutWidth.currentIndented(line)
        );
    }

    /**
     * Tries the width-triggered return branches in precedence order.
     *
     * <p>Method calls and conditionals are tried first because their helpers already know how to force a useful break for
     * the whole expression. Parenthesized-looking values are handled next so the long part moves inside parentheses
     * instead of leaving a wide value directly after {@code return}.
     */
    private Optional<Doc> brokenReturnExpression(Expression expression, LayoutContext layout) {
        Optional<Doc> methodCallChain = returnWithForcedMethodCallChain(expression, layout);
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
        return returnWithParenthesizedValueBreak(expression, layout);
    }

    private Optional<Doc> returnWithForcedMethodCallChain(Expression expression, LayoutContext layout) {
        if (!(expression instanceof MethodCallExpr methodCall)) {
            return Optional.empty();
        }
        // The return keyword's rendered column is threaded through chainLayout's leftEdgePrefix below, so the prefix-aware
        // chain gates (MethodCallChainPrinter.compactRootLineWidth) measure at the true column and the residual
        // fixed-baseline probes only need the ordinary two-unit block baseline ({@link LayoutWidth#blockStatement}).
        ToIntFunction<String> lineWidth = layoutWidth::blockStatement;
        // LDM-2f (#190): the return keyword shares the value's first line, so hand the chain gates that fixed prefix. The
        // gate that reads it (MethodCallChainPrinter.compactRootLineWidth) drops its source-column floor and measures the
        // compact chain root at nodeIndentWidth + "return " + text; every branch that can reach that gate threads the same
        // prefix so the fit decision is identical on every pass (idempotent).
        LayoutContext chainLayout = layout.withLeftEdgePrefix("return ");
        // Canonical-fan cutover seam (End-state A): a fan-threshold, comment/lambda-free return chain fans one selector per
        // line, and it must do so through the SAME source-neutral fan on every pass — otherwise a source-multiline-argument
        // pass folds the first selector onto the value (`return data.configResources()...`) via the imperative
        // {@code canAttachFirstSegmentToSimpleRoot} branch, and the fanned re-format (single-line arguments now) splits it
        // (`return data`⏎`.configResources()...`), flipping split<->attach forever. {@code chainFanOut} is a pure function of
        // the AST, so both passes rebuild the identical fan. This precedes the source-multiline branches below, which are
        // exactly the source-shape-sensitive routes that produce the flip; the fan carries the empty final-segment suffix
        // (the return terminator `;` is appended by {@link #returnStatement} outside the value) and the `return ` left-edge
        // prefix so a promoted factory root measures its opener at the rendered column. Expression-lambda-bodied return
        // chains are withheld inside {@code canonicalFanChain} (deferred lambda-arrow seam), so they fall through to the
        // source-multiline-lambda handling below unchanged.
        Optional<Doc> canonicalFan = canonicalFanChain.apply(methodCall, "", chainLayout);
        if (canonicalFan.isPresent()) {
            return canonicalFan;
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
                    .apply(methodCall, text -> returnLineWidth(methodCall, "return " + text, layout), chainLayout)
                    .or(() -> forcedMethodCallChain.apply(methodCall, lineWidth, chainLayout));
        }
        if (methodCall.getScope().filter(ObjectCreationExpr.class::isInstance).isPresent()) {
            // LDM-3g (#210): a source-compact object-creation-rooted single-segment chain routes through the chain
            // printer's ranked Doc.bestFitting (MethodCallChainPrinter.rankedObjectRootSingleSegmentChain), reached via the
            // forced-chain callee below; it lets the renderer rank compact-with-broken-segment versus one-per-line fan-out
            // at the real column instead of the first-line probe committing to a shape. The probe is still threaded so the
            // deeper/source-multiline object-root shapes the ranker defers on keep their imperative selection.
            return forcedMethodCallChainWithFirstLine
                    .apply(methodCall, text -> returnLineWidth(methodCall, "return " + text, layout), chainLayout)
                    .or(() -> forcedMethodCallChain.apply(methodCall, lineWidth, chainLayout));
        }
        // chain-unify U2 (#190): route the general width-driven return chain's compact-versus-fan verdict through the
        // ranked engine. The chain has two legal broken shapes here — the compact-root-with-broken-final-segment (CRBFS,
        // the compact root plus selector on one line with only the final argument list broken) and the one-per-line
        // fan-out (each selector on its own dotted continuation line, the named arm U1 consolidated). Rather than the
        // imperative "CRBFS first if it fits, else fan" the return caller used before, emit a single Doc.bestFitting so the
        // renderer owns the verdict at the real column (post-"return "), the same way the single-segment/object-root
        // rankers already do inside MethodCallChainPrinter.
        //
        // The CRBFS arm carries priority 1 and the fan arm priority 0 (Mechanism 2, Doc.bestFitting(List, int[])): among
        // the arms that FIT, the higher-priority CRBFS is kept regardless of line count. That reproduces the imperative
        // pre-empt byte-identically — the pre-empt returned CRBFS whenever compactRootWithBrokenFinalChainSegment could
        // build it (its opener fits), i.e. exactly the cases where the CRBFS arm now fits and its priority keeps it. The
        // only place the two differ is when the compact shape overflows at the rendered column (e.g. a chain co-located
        // after an unbroken `if (...) ... else return `, whose deep first line no shape can rescue): the old pre-empt still
        // committed to the over-width CRBFS, whereas priority never rescues an overflowing arm, so bestFitting falls to the
        // fan-out, which uses fewer lines for the same unavoidable overflow — a strict improvement, and idempotent (the
        // renderer re-ranks the same two AST-built candidates every pass). Only comment-free chains build both arms eagerly
        // (the arms render the chain twice and would otherwise double-claim a comment, tripping the strict-claims guardrail
        // — the same gate the landed rankers apply); a comment-bearing chain keeps the imperative cascade, which renders
        // the chosen shape once. chainLayout carries the "return " left-edge prefix into both arms so each candidate is
        // measured at the true rendered column.
        Optional<Doc> compactBrokenSegment =
            compactRootWithBrokenFinalChainSegment.apply(methodCall, lineWidth, chainLayout);
        if (methodCall.getAllContainedComments().isEmpty() && compactBrokenSegment.isPresent()) {
            Optional<Doc> fanOut = forcedMethodCallChainWithFirstLine
                    .apply(methodCall, text -> returnLineWidth(methodCall, "return " + text, layout), chainLayout)
                    .or(() -> forcedMethodCallChain.apply(methodCall, lineWidth, chainLayout));
            if (fanOut.isPresent()) {
                return Optional.of(Doc.bestFitting(
                    List.of(compactBrokenSegment.orElseThrow(), fanOut.orElseThrow()),
                    new int[] {1, 0}
                ));
            }
        }
        return compactBrokenSegment
                .or(() -> forcedMethodCallChainWithFirstLine.apply(
                        methodCall,
                        text -> returnLineWidth(methodCall, "return " + text, layout),
                        chainLayout
                ))
                .or(() -> forcedMethodCallChain.apply(methodCall, lineWidth, chainLayout))
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
    private Optional<Doc> returnWithParenthesizedValueBreak(Expression expression, LayoutContext layout) {
        if (expression instanceof EnclosedExpr enclosedExpr) {
            if (enclosedExpr.getInner() instanceof BinaryExpr binaryExpr) {
                Optional<Doc> directBinary = binaryReturns.directBinaryReturn(binaryExpr, enclosedExpr, layout);
                if (directBinary.isPresent()) {
                    return directBinary;
                }
            }
            return Optional.of(parenthesizedBreak.apply(enclosedExpr.getInner(), false));
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            Optional<Doc> directBinary = binaryReturns.directBinaryReturn(binaryExpr, layout);
            if (directBinary.isPresent()) {
                return directBinary;
            }
            // A bare (unparenthesized) binary return value that {@code directBinaryReturn} cannot lay out
            // flat/first-line-fit breaks operand-per-line through {@code binaryLines} WITHOUT adding parentheses — the
            // author wrote none and the value is not enclosed. {@code directBinaryReturn} returns empty here, so fall back
            // to the same operand-per-line skeleton the direct-binary branch itself emits
            // ({@code Doc.indent(binaryLines(expr, true))}) rather than wrapping the value in a spurious
            // {@code return (}⏎{@code …}⏎{@code )}.
            return Optional.of(Doc.indent(binaryLines.apply(binaryExpr, true)));
        }
        return Optional.empty();
    }
}
