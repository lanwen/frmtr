package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Renders Java conditional expressions and their assignment or initializer-specific break decisions.
 *
 * <p>This helper owns the ternary decision tree around flat versus broken {@code ?:} output, line comments attached near
 * {@code ?} and {@code :}, nested conditional branches, and binary-condition wrapping when a conditional expression is
 * used as an assignment value or variable initializer. The boundary exists because {@link ConditionalExpr} nodes appear
 * in several caller contexts, but the ternary-specific comment and width decisions are the same once those callers have
 * decided that they need conditional expression formatting.
 *
 * <p>{@link JavaPrinter} still owns general expression dispatch, assignment dispatch, raw source and pragma gates, field
 * declaration layout, and binary-expression policy. This helper receives those decisions as callbacks and only chooses
 * the shape of the conditional expression itself. Representative fixture pairs for this boundary include
 * {@code frmtr-core/src/test/resources/format/conditional-expression-space-indentation/input.java} with
 * {@code frmtr-core/src/test/resources/format/conditional-expression-space-indentation/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/conditional-expression-tab-indentation/input.java} with
 * {@code frmtr-core/src/test/resources/format/conditional-expression-tab-indentation/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/variable-declarations/input.java} with
 * {@code frmtr-core/src/test/resources/format/variable-declarations/frmtr-default.output.java}, and
 * {@code frmtr-core/src/test/resources/format/expression-operators-layout/input.java} with
 * {@code frmtr-core/src/test/resources/format/expression-operators-layout/frmtr-default.output.java}.
 */
final class ConditionalExpressionPrinter {

    private final ConditionalCommentLayout conditionalComments;

    private final FormatterOptions options;

    private final SourceShapePolicy sourceShapePolicy;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final LayoutDecisionLog layoutDecisions;

    private final ExpressionRendering rendering;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer;

    private final BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer;

    private final Predicate<Expression> expressionHasParenthesizedNestedBinary;

    /**
     * Names whether conditional-expression layout is caller-forced or selected by local width and comment checks.
     *
     * <p>The enum owns only the ternary break mode. Assignment, return, field, and enclosed-expression callers still
     * decide when their surrounding context requires the forced mode.
     */
    private enum ConditionalBreakMode {
        /** Keep the conditional flat when source-equivalent text fits and no comments or nesting require rebuilding. */
        AUTO,

        /** Print the broken ternary shape because a caller has already selected a multiline conditional context. */
        FORCED;

        static ConditionalBreakMode fromForced(boolean forced) {
            return forced ? FORCED : AUTO;
        }

        boolean isForced() {
            return this == FORCED;
        }
    }

    /**
     * Names how a conditional expression's condition should render when the condition itself is a long binary tree.
     *
     * <p>The enum owns only the condition sub-layout. It leaves branch rendering, assignment detection, and the binary
     * expression continuation policy with their existing owners.
     */
    private enum ConditionalConditionLayout {
        /** Render the condition through ordinary expression dispatch. */
        EXPRESSION,

        /** Render a wide binary condition with the assignment/initializer continuation shape. */
        ASSIGNMENT_CONTINUATION_BINARY,

        /** Render a wide binary condition with nested-expression continuation indentation. */
        NESTED_BINARY,
    }

    ConditionalExpressionPrinter(
            JavaFormatContext context,
            ExpressionRendering rendering,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer,
            BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer,
            Predicate<Expression> expressionHasParenthesizedNestedBinary
    ) {
        this.options = context.options;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.compactSource = context.compactSource;
        this.layoutWidth = context.layoutWidth;
        this.layoutDecisions = context.layoutDecisions;
        this.rendering = rendering;
        this.binaryExpressionLinesRenderer = binaryExpressionLinesRenderer;
        this.nestedBinaryExpressionLinesRenderer = nestedBinaryExpressionLinesRenderer;
        this.expressionHasParenthesizedNestedBinary = expressionHasParenthesizedNestedBinary;
        this.conditionalComments = new ConditionalCommentLayout(
            context.comments,
            context.commentPlacementPolicy,
            rendering
        );
    }

    /**
     * Chooses the shape for an assignment whose value is a conditional expression.
     *
     * <p>When the condition itself is structurally complex, the whole conditional moves under the assignment operator so
     * the reader sees the assignment first and then the ternary tree. If only the full expression is too wide, but the
     * target, operator, and condition still fit, the condition stays after {@code =} and only the {@code ?} and
     * {@code :} branches break below it.
     */
    Optional<Doc> assignmentWithConditionalValue(AssignExpr assignExpr, ConditionalExpr conditionalExpr) {
        if (
            shouldBreakBeforeConditionalInitializer(conditionalExpr)
            || shouldBreakBeforeConditionalAssignment(conditionalExpr)
        ) {
            return Optional.of(
                Doc.concat(
                    rendering.render(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString()),
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)
                        )
                    )
                )
            );
        }
        String conditionLine = compactSource.compact(assignExpr.getTarget())
            + " "
            + assignExpr.getOperator().asString()
            + " "
            + compactSource.compact(conditionalExpr.getCondition())
            + ";";
        // C10-c: measure the {@code target op condition;} line at the conditional's true rendered block/type depth
        // ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
        if (layoutWidth.nodeLine(conditionalExpr, conditionLine) <= options.lineWidth()) {
            return Optional.of(
                Doc.concat(
                    rendering.render(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)
                )
            );
        }
        return Optional.empty();
    }

    /**
     * Reports whether initializer callers should start the whole conditional on the next line after {@code =}.
     *
     * <p>A binary condition combined with a binary branch creates a multi-part ternary where keeping the condition after
     * the initializer name makes the tree harder to scan; callers use this fork to put the whole conditional under the
     * assignment instead.
     */
    boolean shouldBreakBeforeConditionalInitializer(ConditionalExpr initializer) {
        return initializer.getCondition() instanceof BinaryExpr
            && (initializer.getThenExpr() instanceof BinaryExpr || initializer.getElseExpr() instanceof BinaryExpr);
    }

    private boolean shouldBreakBeforeConditionalAssignment(ConditionalExpr conditionalExpr) {
        return conditionalExpr.getCondition() instanceof BinaryExpr binaryExpr
            && binaryExpr.findAll(MethodCallExpr.class).stream().findAny().isPresent();
    }

    Doc conditionalExpression(ConditionalExpr expression) {
        return conditionalExpression(expression, ConditionalBreakMode.AUTO);
    }

    /**
     * Prints a conditional expression, preserving flat output until width, nesting, comments, or caller context require
     * the broken ternary shape.
     */
    Doc conditionalExpression(ConditionalExpr expression, boolean forceBreak) {
        return conditionalExpression(expression, ConditionalBreakMode.fromForced(forceBreak));
    }

    private Doc conditionalExpression(ConditionalExpr expression, ConditionalBreakMode breakMode) {
        Optional<Doc> commented = conditionalComments.commentedConditionalExpression(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        // A caller-forced context prints the broken ternary shape unconditionally: that is a caller decision, not a width
        // decision, so it never consults the renderer's column. This is the imperative path: the broken shape is built
        // directly as HardLines, so the renderer only ever sees a forced break and cannot narrate the width arithmetic.
        // When the caller forced this conditional apart *because* its flat form overflowed (an assignment/initializer
        // whose one-line ternary was too wide), record that width decision so --explain can attribute the wrap to width.
        // Recording runs after the broken shape is chosen and never influences the produced Doc, so output stays
        // byte-identical. A conditional the author happened to write across multiple source lines is NOT forced here —
        // it reflows through the width-driven auto path below like any other, per reprint-by-default canonicalization.
        //
        // A conditional that carries a line comment the flat arm cannot represent is forced broken as well: this is a
        // comment-safety gate (COMMENT_PRESENCE_GATE), not a source-shape read.
        // {@link ConditionalCommentLayout#commentedConditionalExpression}
        // above already handles comments that sit around this ternary's own {@code ?}/{@code :} operators; the remaining
        // line comments live inside a branch sub-expression (e.g. a binary branch's between-operand {@code //} comment),
        // which the flat {@code compact} arm would swallow to end-of-line. The broken shape renders each branch through
        // the ordinary expression renderer, which places those inner comments correctly.
        if (breakMode.isForced() || conditionalComments.conditionalContainsLineComment(expression)) {
            recordTernaryWidthBreak(expression);
            return brokenConditionalExpression(expression);
        }
        // Auto path: defer the flat-versus-broken width decision to the renderer, which measures the flat arm at the
        // true running column via Doc.conditionalGroup. The earlier gate probed a reconstructed indented width, which
        // disagreed with the column write reaches (the #137/#155 width-at-wrong-column family); a disagreement could
        // print a genuinely over-width ternary flat and let a later pass break it. Measuring at the real column removes
        // that reconstruction, and DocExplainRenderer already reports the group decision at available = lineWidth -
        // column, so no separate width-break recorder is needed.
        return Doc.conditionalGroup(
            List.of(flatConditionalExpression(expression), brokenConditionalExpression(expression))
        );
    }

    /**
     * Builds the single-line ternary layout the renderer renders when the flat form fits the columns left.
     *
     * <p>A ternary whose condition holds a parenthesized nested binary is rebuilt from its parts so the condition passes
     * through the binary continuation policy even in flat form; every other ternary is the compact source text.
     */
    private Doc flatConditionalExpression(ConditionalExpr expression) {
        if (expressionHasParenthesizedNestedBinary.test(expression)) {
            return Doc.concat(
                conditionalCondition(expression),
                Doc.text(" ? "),
                conditionalBranch(expression.getThenExpr()),
                Doc.text(" : "),
                conditionalBranch(expression.getElseExpr())
            );
        }
        return Doc.text(compactSource.compact(expression));
    }

    /**
     * Records the ternary's flat-width decision when the imperative (caller-forced or source-multiline) path breaks a
     * conditional whose single-line form overflowed the line budget, so explain can attribute the wrap to width rather
     * than to an opaque forced break.
     *
     * <p>This fires only for genuine width breaks: a conditional forced apart for nesting or a source-multiline shape
     * while its flat form would still fit is not a width decision and is left unrecorded, so it stays reported as a
     * rule-driven break. The auto path does not call this — it defers the flat-versus-broken choice to
     * {@link Doc#conditionalGroup}, whose {@code ConditionalGroupDecision} self-explains the same width arithmetic; a
     * recorder there would double-report the {@code java.expression:ConditionalExpr} label. Recording runs after the
     * broken shape is chosen and appends only to the observational {@link LayoutDecisionLog}, so it never changes which
     * {@link Doc} is built or the rendered output.
     */
    private void recordTernaryWidthBreak(ConditionalExpr expression) {
        String flat = compactSource.compact(expression);
        // C10-c: narrate the flat ternary width at its true rendered block/type depth ({@link LayoutWidth#nodeLine})
        // instead of the fixed CURRENT baseline. This is observational (feeds --explain only) and never changes the Doc.
        int flatWidth = layoutWidth.nodeLine(expression, flat);
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        layoutDecisions.recordWidthBreak(
            "ternary",
            "java.expression:" + expression.getClass().getSimpleName(),
            ternaryPreview(flat),
            flatWidth,
            options.lineWidth(),
            0
        );
    }

    /**
     * Builds a short headline snippet of the ternary up to and including the {@code ?}, so the reader recognizes the
     * conditional without seeing both branches.
     */
    private String ternaryPreview(String flat) {
        int question = flat.indexOf('?');
        return question < 0 ? flat : flat.substring(0, question + 1) + " …";
    }

    private Doc brokenConditionalExpression(ConditionalExpr expression) {
        return Doc.concat(
            conditionalCondition(expression),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.text("? "),
                    conditionalBranch(expression.getThenExpr()),
                    conditionalComments.branchTailTrailingComment(expression.getThenExpr()),
                    Doc.HARD_LINE,
                    Doc.text(": "),
                    conditionalBranch(expression.getElseExpr())
                )
            )
        );
    }

    /**
     * Prints the ternary condition and chooses the binary wrapping shape for long binary conditions.
     *
     * <p>When a conditional is the value of an assignment or variable initializer, its condition is already under an
     * assignment continuation, so regular binary lines keep the indentation stable. In nested expression contexts,
     * nested binary lines add the extra continuation shape that makes the inner expression read as subordinate to the
     * outer one.
     */
    private Doc conditionalCondition(ConditionalExpr expression) {
        Expression condition = expression.getCondition();
        return switch (conditionalConditionLayout(expression, condition)) {
            case EXPRESSION -> rendering.render(condition);
            case ASSIGNMENT_CONTINUATION_BINARY -> enclosedBinaryCondition(condition, binaryExpressionLinesRenderer);
            case NESTED_BINARY -> enclosedBinaryCondition(condition, nestedBinaryExpressionLinesRenderer);
        };
    }

    private ConditionalConditionLayout conditionalConditionLayout(
            ConditionalExpr expression,
            Expression condition
    ) {
        if (
            binaryCondition(condition).isEmpty()
            || sourceShapePolicy.fitsOnOneLine(condition, layoutWidth::continuationStatement)
        ) {
            return ConditionalConditionLayout.EXPRESSION;
        }
        if (conditionalIsAssignmentValue(expression) || conditionalIsVariableInitializer(expression)) {
            return ConditionalConditionLayout.ASSIGNMENT_CONTINUATION_BINARY;
        }
        return ConditionalConditionLayout.NESTED_BINARY;
    }

    private Doc enclosedBinaryCondition(
            Expression condition,
            BiFunction<Expression, Boolean, Doc> binaryRenderer
    ) {
        BinaryExpr binary = binaryCondition(condition).orElseThrow();
        Doc lines = binaryRenderer.apply(binary, true);
        for (int i = 0; i < enclosedDepth(condition); i++) {
            lines = Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, lines)),
                Doc.HARD_LINE,
                Doc.text(")")
            );
        }
        return lines;
    }

    private Optional<BinaryExpr> binaryCondition(Expression condition) {
        if (condition instanceof BinaryExpr binaryExpr) {
            return Optional.of(binaryExpr);
        }
        if (condition instanceof EnclosedExpr enclosedExpr) {
            return binaryCondition(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    private int enclosedDepth(Expression condition) {
        int depth = 0;
        Expression current = condition;
        while (current instanceof EnclosedExpr enclosedExpr) {
            depth++;
            current = enclosedExpr.getInner();
        }
        return depth;
    }

    private boolean conditionalIsAssignmentValue(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(AssignExpr.class::isInstance)
                .map(AssignExpr.class::cast)
                .filter(assignExpr -> assignExpr.getValue() == expression)
                .isPresent();
    }

    private boolean conditionalIsVariableInitializer(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .flatMap(VariableDeclarator::getInitializer)
                .filter(initializer -> initializer == expression)
                .isPresent();
    }

    private Doc conditionalBranch(Expression branch) {
        if (branch instanceof ConditionalExpr conditionalExpr) {
            return conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED);
        }
        return rendering.render(branch);
    }
}
