package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders assignment expressions after broad expression dispatch has selected {@code target op value} syntax.
 *
 * <p>This helper owns the assignment-specific width decision tree: compact fallback assembly, the statement-width gate
 * that activates broken assignment shapes, suffixed enclosed values, binary-value continuations, anonymous-class-free
 * object creations, method-call value hooks, conditional value hooks, and nested assignment continuations. The boundary
 * exists because assignments are ordinary expressions, but they make context-specific choices about when the value should
 * move below the operator.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact source text, statement width calculations,
 * enclosed suffix handling, binary expression layout, object creation layout, method-call assignment layout, and
 * conditional assignment layout. This helper receives those decisions as callbacks and only walks the assignment
 * decision tree.
 */
final class AssignmentExpressionPrinter {

    private final FormatterOptions options;

    private final CommentTracker comments;

    private final ExpressionRendering rendering;

    private final ExpressionTailRenderer expressionWithTail;

    private final Function<Node, String> compact;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final ToIntFunction<String> blockStatementWidth;

    private final BiFunction<Expression, LayoutContext, Optional<Doc>> suffixedEnclosedExpression;

    private final Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat;

    private final Predicate<BinaryExpr> binaryExpressionHasLineComments;

    private final Function<BinaryExpr, Doc> binaryExpressionLinesWithComments;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLines;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreation;

    private final BiFunction<AssignExpr, MethodCallExpr, Optional<Doc>> methodCallAssignment;

    private final BiFunction<AssignExpr, MethodCallExpr, Optional<Doc>> methodCallAssignmentWithSemicolon;

    private final BiFunction<AssignExpr, ConditionalExpr, Optional<Doc>> conditionalAssignment;

    // Comment-recovery cluster: the assignment statement's terminator trailing-comment family (method-call value tail
    // line comment before the {@code ;}, binary value after-final-operand pre-{@code ;} comment). See
    // {@link AssignmentStatementCommentLayout}.
    private final AssignmentStatementCommentLayout statementCommentLayout;

    AssignmentExpressionPrinter(
            FormatterOptions options,
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            ExpressionRendering rendering,
            ExpressionTailRenderer expressionWithTail,
            Function<Node, String> compact,
            ToIntFunction<String> blockStatementWidth,
            BiFunction<Expression, LayoutContext, Optional<Doc>> suffixedEnclosedExpression,
            Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat,
            Predicate<BinaryExpr> binaryExpressionHasLineComments,
            Function<BinaryExpr, Doc> binaryExpressionLinesWithComments,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLines,
            Function<ObjectCreationExpr, Doc> brokenObjectCreation,
            BiFunction<AssignExpr, MethodCallExpr, Optional<Doc>> methodCallAssignment,
            BiFunction<AssignExpr, MethodCallExpr, Optional<Doc>> methodCallAssignmentWithSemicolon,
            BiFunction<AssignExpr, ConditionalExpr, Optional<Doc>> conditionalAssignment
    ) {
        this.options = options;
        this.comments = comments;
        this.rendering = rendering;
        this.expressionWithTail = expressionWithTail;
        this.compact = compact;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compact::apply);
        this.blockStatementWidth = blockStatementWidth;
        this.suffixedEnclosedExpression = suffixedEnclosedExpression;
        this.shouldKeepCastDivisionContinuationFlat = shouldKeepCastDivisionContinuationFlat;
        this.binaryExpressionHasLineComments = binaryExpressionHasLineComments;
        this.binaryExpressionLinesWithComments = binaryExpressionLinesWithComments;
        this.binaryExpressionLines = binaryExpressionLines;
        this.brokenObjectCreation = brokenObjectCreation;
        this.methodCallAssignment = methodCallAssignment;
        this.methodCallAssignmentWithSemicolon = methodCallAssignmentWithSemicolon;
        this.conditionalAssignment = conditionalAssignment;
        this.statementCommentLayout = new AssignmentStatementCommentLayout(comments, commentPlacement);
    }

    /**
     * Prints an assignment flat unless the whole expression statement would exceed the configured line width.
     *
     * <p>The break tree is intentionally gated by {@code flat + ";"} because assignment expressions most often appear as
     * expression statements. If that full statement fits, the formatter preserves the compact {@code target op value}
     * shape even when the value has sub-shapes that could break in other contexts.
     */
    Doc assignment(AssignExpr expression) {
        List<Doc> gapLineComments = gapLineComments(expression);
        if (!gapLineComments.isEmpty()) {
            return assignmentWithGapLineComments(expression, gapLineComments);
        }
        if (
            expression.getValue() instanceof BinaryExpr binaryValue
            && binaryExpressionHasLineComments.test(binaryValue)
        ) {
            return assignmentWithLineCommentedBinaryValue(expression, binaryValue);
        }
        String flat = compact.apply(expression);
        if (
            expression.getValue() instanceof ConditionalExpr conditionalExpr
            && conditionalAssignmentLineOverflows(expression, conditionalExpr)
        ) {
            Optional<Doc> conditionalValue = conditionalAssignment.apply(expression, conditionalExpr);
            if (conditionalValue.isPresent()) {
                return conditionalValue.orElseThrow();
            }
        }
        if (blockStatementWidth.applyAsInt(flat + ";") > options.lineWidth()) {
            Optional<Doc> brokenAssignment = brokenAssignment(expression);
            if (brokenAssignment.isPresent()) {
                return brokenAssignment.orElseThrow();
            }
        }
        return flatAssignment(expression);
    }

    private boolean conditionalAssignmentLineOverflows(AssignExpr expression, ConditionalExpr conditionalExpr) {
        String line = compact.apply(expression.getTarget())
            + " "
            + expression.getOperator().asString()
            + " "
            + conditionalProjection.line(conditionalExpr)
            + ";";
        return blockStatementWidth.applyAsInt(line) > options.lineWidth();
    }

    Doc assignmentStatement(AssignExpr expression) {
        String flat = compact.apply(expression);
        if (
            expression.getValue() instanceof MethodCallExpr methodCall
            && statementCommentLayout.methodCallNeedsStatementTerminatorTail(expression, methodCall)
        ) {
            return Doc.concat(
                rendering.render(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " "),
                expressionWithTail.render(methodCall, ExpressionTail.SEMICOLON, blockStatementWidth),
                // The assigned value's own final trailing line comment is owned and emitted inside the method-chain
                // render above. Offering it here under this assignment's own INTERLEAVED anchor lets comment ownership
                // disambiguate: when the chain render owns it, this slot is not the recorded owner and comment(...)
                // returns Doc.EMPTY (caught by the != Doc.EMPTY filter below); a comment the chain render left untouched
                // (the comment-free compact path) is owned here and placed by this slot. Anchoring to the distinct
                // (expression, INTERLEAVED) key rather than the comment's own node is what makes the ownership gate
                // sufficient, so no build-order isPrinted skip is needed.
                statementCommentLayout.assignmentValueTailLineComment(expression, methodCall)
                        .map(comment -> comments.comment(comment, expression, OwnerSlot.INTERLEAVED))
                        .filter(comment -> comment != Doc.EMPTY)
                        .map(comment -> Doc.concat(Doc.text(" "), comment))
                        .orElse(Doc.EMPTY)
            );
        }
        if (
            blockStatementWidth.applyAsInt(flat + ";") > options.lineWidth()
            && expression.getValue() instanceof MethodCallExpr methodCall
        ) {
            Optional<Doc> methodCallValue = methodCallAssignmentWithSemicolon.apply(expression, methodCall);
            if (methodCallValue.isPresent()) {
                return methodCallValue.orElseThrow();
            }
        }
        Doc preSemicolonBinaryComment = statementCommentLayout.preSemicolonBinaryValueComment(expression);
        return Doc.concat(assignment(expression), Doc.text(";"), preSemicolonBinaryComment);
    }

    /**
     * The width-triggered broken assignment value arms, keyed on the assigned value's AST kind. The suffixed-enclosed
     * shape is tried first, <em>outside</em> this enum, because it fires for a suffix after an enclosed receiver
     * regardless of the value's kind; every arm below is a single, mutually exclusive value kind.
     */
    private enum AssignmentValueArm {
        /** Binary value moved below the operator (cast-division continuation kept flat when it still fits). */
        BINARY,
        /** Non-anonymous object creation with its argument list broken under the assignment. */
        OBJECT_CREATION,
        /** Method-call value shaped by the method-call printer. */
        METHOD_CALL,
        /** Conditional value routed to the conditional assignment hook. */
        CONDITIONAL,
        /** Nested assignment moved onto the continuation line. */
        NESTED_ASSIGNMENT,
        /** No construct-specific broken shape; the cascade declines and the caller falls back to flat. */
        NONE,
    }

    /**
     * Tries the width-triggered assignment branches in order of local layout-constraint strength.
     *
     * <p>The suffixed-enclosed shape must stay attached to its own broken receiver, so it is tried first regardless of
     * value kind. When it declines, the value's AST kind selects one {@link AssignmentValueArm}; each arm has stronger
     * local constraints than plain flat assembly (binary operator position, constructor argument breaking,
     * ternary/method-call hooks). An arm that declines leaves the cascade empty, so the caller falls back to flat.
     */
    private Optional<Doc> brokenAssignment(AssignExpr expression) {
        Optional<Doc> suffixedEnclosedValue = assignmentWithSuffixedEnclosedValue(expression);
        if (suffixedEnclosedValue.isPresent()) {
            return suffixedEnclosedValue;
        }
        return renderAssignmentValueArm(classifyAssignmentValue(expression.getValue()), expression);
    }

    /**
     * Selects the {@link AssignmentValueArm} for a broken assignment value from its AST kind alone. The kinds are
     * mutually exclusive, so this switch is order-independent; an anonymous-class object creation matches no arm (its
     * body owns the layout) and resolves to {@link AssignmentValueArm#NONE}.
     */
    private AssignmentValueArm classifyAssignmentValue(Expression value) {
        return switch (value) {
            case BinaryExpr binaryValue -> AssignmentValueArm.BINARY;
            // An anonymous-class body owns the layout after the constructor header, so it takes no broken shape here and
            // declines to NONE (empty -> flat).
            case ObjectCreationExpr objectCreation -> objectCreation.getAnonymousClassBody().isEmpty()
                ? AssignmentValueArm.OBJECT_CREATION
                : AssignmentValueArm.NONE;
            case MethodCallExpr methodCall -> AssignmentValueArm.METHOD_CALL;
            case ConditionalExpr conditional -> AssignmentValueArm.CONDITIONAL;
            case AssignExpr nestedAssignment -> AssignmentValueArm.NESTED_ASSIGNMENT;
            default -> AssignmentValueArm.NONE;
        };
    }

    /**
     * Dispatches a classified {@link AssignmentValueArm} to its shape emitter, returning the {@code Optional<Doc>} that
     * emitter produces -- including the empty the {@code METHOD_CALL} and {@code CONDITIONAL} hooks may return, which
     * leaves the cascade empty and falls the caller back to flat assignment.
     */
    private Optional<Doc> renderAssignmentValueArm(AssignmentValueArm arm, AssignExpr expression) {
        return switch (arm) {
            case BINARY -> assignmentWithBinaryValue(expression);
            case OBJECT_CREATION -> assignmentWithObjectCreationValue(expression);
            case METHOD_CALL -> assignmentWithMethodCallValue(expression);
            case CONDITIONAL -> assignmentWithConditionalValue(expression);
            case NESTED_ASSIGNMENT -> assignmentWithNestedAssignmentValue(expression);
            case NONE -> Optional.empty();
        };
    }

    /**
     * Lets parenthesized/enclosed suffix renderers keep the suffix attached to their own broken receiver.
     *
     * <p>This is tried before value-kind checks because a method call, array access, or method reference after an
     * enclosed receiver has already decided where its suffix must appear. The assignment only supplies the
     * {@code target op} prefix and leaves that suffix-sensitive shape intact.
     */
    private Optional<Doc> assignmentWithSuffixedEnclosedValue(AssignExpr expression) {
        // The assignment has already decided this value breaks, so the enclosed suffix receiver must lead with a break;
        // that positional fact rides on the LayoutContext rather than a loose boolean argument.
        Optional<Doc> suffixedEnclosedValue = suffixedEnclosedExpression.apply(
            expression.getValue(),
            LayoutContext.root().withLeadingBreak(true)
        );
        return suffixedEnclosedValue.map(value -> Doc.concat(
                rendering.render(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " "),
                value
        ));
    }

    /**
     * Moves a binary value below the assignment operator while preserving binary-specific continuation policy.
     *
     * <p>The cast-division exception delegates the decision to {@link BinaryExpressionPrinter}: {@code x = (T) a / b}
     * stays as one continuation line when that line still fits, so the cast remains visually attached to the divided
     * value. Other binary values use the shared broken binary lines because operator placement and operand wrapping are
     * not assignment-specific decisions.
     */
    private Optional<Doc> assignmentWithBinaryValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof BinaryExpr binaryExpression)) {
            return Optional.empty();
        }
        if (shouldKeepCastDivisionContinuationFlat.test(binaryExpression)) {
            return Optional.of(
                Doc.concat(
                    rendering.render(expression.getTarget()),
                    Doc.text(" " + expression.getOperator().asString()),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, rendering.render(binaryExpression)))
                )
            );
        }
        return Optional.of(
            Doc.concat(
                rendering.render(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString()),
                Doc.indent(
                    Doc.concat(Doc.HARD_LINE, binaryExpressionLines.apply(expression.getValue(), true))
                )
            )
        );
    }

    /**
     * Lays out a binary assignment value whose operands carry {@code //} line comments one operator per line under the
     * assignment's continuation indent, mirroring {@code VariableInitializerLayout}'s line-commented binary initializer.
     *
     * <p>A line comment on or between operands forces the binary to break one operator per line even when the
     * comment-free statement would fit ({@link BinaryExpressionPrinter#binaryExpression} routes such a binary to its
     * comment-aware multi-line render). The default expression dispatch that {@link #flatAssignment} relies on emits
     * those comment-aware lines with no continuation indent, so the operands after the first stranded at the statement's
     * own indent instead of sitting under the assignment like every other wrapped binary value. This branch claims that
     * case up front so the continuation operands are wrapped in the assignment continuation {@link Doc#indent(Doc)} the
     * same way the width-driven {@link #assignmentWithBinaryValue} path indents its operands.
     *
     * <p>The first operand stays on the {@code target op} line when it still fits there, matching the source shape the
     * comment was annotating; otherwise the whole value drops below the operator. The trailing comment after the final
     * operand is still recovered and deferred past the {@code ;} by {@link #preSemicolonBinaryValueComment} in
     * {@link #assignmentStatement}, so this branch only owns operand indentation and never the after-final-operand
     * comment placement.
     */
    private Doc assignmentWithLineCommentedBinaryValue(AssignExpr expression, BinaryExpr binaryValue) {
        Doc target = rendering.render(expression.getTarget());
        String operator = expression.getOperator().asString();
        Doc lines = binaryExpressionLinesWithComments.apply(binaryValue);
        if (lineCommentedBinaryCanKeepFirstOperandWithOperator(expression, binaryValue)) {
            return Doc.concat(target, Doc.text(" " + operator + " "), Doc.indent(lines));
        }
        return Doc.concat(
            target,
            Doc.text(" " + operator),
            Doc.indent(Doc.concat(Doc.HARD_LINE, lines))
        );
    }

    /**
     * Reports whether the first operand of a line-commented binary value still fits on the {@code target op} line, so the
     * value can keep its source shape instead of dropping the whole binary below the operator.
     */
    private boolean lineCommentedBinaryCanKeepFirstOperandWithOperator(AssignExpr expression, BinaryExpr binaryValue) {
        String firstOperandLine = compact.apply(expression.getTarget())
            + " "
            + expression.getOperator().asString()
            + " "
            + compact.apply(firstBinaryOperand(binaryValue));
        return blockStatementWidth.applyAsInt(firstOperandLine) <= options.lineWidth();
    }

    private Expression firstBinaryOperand(BinaryExpr binaryValue) {
        Expression left = binaryValue.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == binaryValue.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }

    /**
     * Breaks constructor-call arguments under the assignment when there is no anonymous class body.
     *
     * <p>Anonymous classes own the layout after their constructor header, so this branch only handles ordinary object
     * creations and leaves anonymous-class member sequencing to {@link ObjectCreationPrinter}.
     */
    private Optional<Doc> assignmentWithObjectCreationValue(AssignExpr expression) {
        if (
            !(expression.getValue() instanceof ObjectCreationExpr objectCreationExpression)
            || objectCreationExpression.getAnonymousClassBody().isPresent()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                rendering.render(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " "),
                brokenObjectCreation.apply(objectCreationExpression)
            )
        );
    }

    /**
     * Defers method-call assignment wrapping to the method-call printer.
     *
     * <p>That printer already knows whether the call prefix fits after {@code target op} and whether the argument list
     * can break without moving the whole value. The assignment helper only asks for that specialized shape when the value
     * is a direct method call.
     */
    private Optional<Doc> assignmentWithMethodCallValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof MethodCallExpr methodCall)) {
            return Optional.empty();
        }
        return methodCallAssignment.apply(expression, methodCall);
    }

    /**
     * Defers ternary assignment wrapping to the conditional printer.
     *
     * <p>Conditional expressions have their own fork for keeping the condition on the assignment line versus moving the
     * whole ternary below the operator, so this helper only routes direct conditional values into that existing policy.
     */
    private Optional<Doc> assignmentWithConditionalValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof ConditionalExpr conditionalExpression)) {
            return Optional.empty();
        }
        return conditionalAssignment.apply(expression, conditionalExpression);
    }

    /**
     * Moves a nested assignment onto the continuation line after the outer operator.
     *
     * <p>Nested assignments are not passed through another specialized helper because the existing expression dispatch
     * can render the inner assignment recursively once the outer assignment has chosen the continuation boundary.
     */
    private Optional<Doc> assignmentWithNestedAssignmentValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof AssignExpr nestedAssignment)) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                rendering.render(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, rendering.render(nestedAssignment)))
            )
        );
    }

    private Doc flatAssignment(AssignExpr expression) {
        Optional<String> gapBlockComment = gapBlockComment(expression);
        if (gapBlockComment.isPresent()) {
            return Doc.concat(
                rendering.render(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " " + gapBlockComment.orElseThrow() + " "),
                rendering.renderWithoutOwnComment(expression.getValue())
            );
        }
        return Doc.concat(
            rendering.render(expression.getTarget()),
            Doc.text(" " + expression.getOperator().asString() + " "),
            rendering.render(expression.getValue())
        );
    }

    /**
     * Renders an assignment whose {@code =}-to-value gap carries one or more {@code //} line comments: the comments are
     * emitted right after the operator on the {@code =} line and the value wraps onto its own indented line below.
     *
     * <p>The comments must move below-the-operator regardless of width because a {@code //} line comment would otherwise
     * swallow the value text that follows it on the same line. The value is rendered with the same force-broken value
     * shape the width-driven assignment branches use so a multi-operand right-hand side still breaks one operator per
     * line under the comment, matching the source the comment was annotating.
     */
    private Doc assignmentWithGapLineComments(AssignExpr expression, List<Doc> gapComments) {
        return Doc.concat(
            rendering.render(expression.getTarget()),
            Doc.text(" " + expression.getOperator().asString() + " "),
            Doc.join(Doc.HARD_LINE, gapComments),
            Doc.indent(Doc.concat(Doc.HARD_LINE, gapCommentValue(expression)))
        );
    }

    /**
     * Picks the value shape rendered below the gap line comment, forcing a multi-operand binary value to break one
     * operator per line so the wrapped right-hand side reads the same as in source.
     *
     * <p>Binary values route through the shared broken-binary lines (honoring the cast-division continuation exception)
     * because the bare expression renderer keeps a top-level binary flat even when it overflows; every other value kind
     * keeps its own internal layout via the shared expression renderer.
     */
    private Doc gapCommentValue(AssignExpr expression) {
        Expression value = expression.getValue();
        if (
            value instanceof BinaryExpr binaryExpression
            && !shouldKeepCastDivisionContinuationFlat.test(binaryExpression)
        ) {
            return binaryExpressionLines.apply(value, true);
        }
        return rendering.render(value);
    }

    /**
     * Recovers and claims the {@code //} line comments that sit in the {@code =}-to-value gap of an assignment, gathering
     * from whichever bucket JavaParser parked them in rather than from a single fixed association.
     *
     * <p>A {@code target = // note} comment lands on the assignment <em>target</em> as its own trailing line comment when
     * the comment hugs the {@code =} line (the collapsed/default shape), but a whitespace perturbation that pushes the
     * comment onto its own line re-buckets the identical comment onto the {@link AssignExpr} as an orphan instead. The
     * shared target renderer surfaces neither bucket, so the assignment must claim and place the gap comment here or it is
     * dropped entirely. Selecting purely by source position (strictly after the target ends, strictly before the value
     * begins) keeps the same comment owned across both shapes; a comment that trails the whole assignment attaches to the
     * value or the enclosing statement and lies outside the gap, so it is never claimed here.
     */
    private List<Doc> gapLineComments(AssignExpr expression) {
        return comments.gapLineCommentsBefore(
            expression.getTarget(),
            expression.getValue(),
            List.of(expression, expression.getTarget())
        );
    }

    /**
     * Finds and claims a block comment that sits in the {@code =}-to-value gap as the value's own block comment.
     *
     * <p>This mirrors {@code VariableInitializerLayout.postEqualsBlockComment}: the comment is the assigned value's own
     * block comment positioned after the operator, and the shared expression renderer drops that own comment for some
     * value kinds (for example method calls and name expressions), so the assignment must own its placement. The
     * operator-position check keys off the assignment's raw token text so a value's leading block comment that begins
     * before the operator is left to the value renderer. For value kinds whose renderer already emits the comment the
     * explicit text is byte-identical, and claiming it once keeps the comment accounted for either way.
     *
     * <p>The {@code /*} probe only proves some block comment exists after the operator; it can also be an interspersed
     * comment buried inside a multi-line method-call-chain value (for example {@code .define(A, INT) /* doc *}{@code /}).
     * That comment belongs to a chain link, not to the value's own pre-render gap, so {@link CommentTracker#ownComment}
     * returns {@link Doc#EMPTY} for it. Returning that empty text as a gap comment would interpolate an empty string
     * between two single spaces and double-space the operator ({@code "=  "}). Mirroring
     * {@code VariableInitializerLayout.postEqualsBlockComment}, treat {@link Doc#EMPTY} as "no gap comment" so only a
     * genuine, non-empty own block comment in the gap is claimed here.
     */
    private Optional<String> gapBlockComment(AssignExpr expression) {
        String raw = expression.getTokenRange()
                .map(Object::toString)
                .orElseGet(expression::toString);
        int operator = raw.indexOf(expression.getOperator().asString());
        int blockComment = raw.indexOf("/*");
        if (blockComment < 0 || operator < 0 || blockComment < operator) {
            return Optional.empty();
        }
        Doc comment = comments.ownComment(expression.getValue(), BlockComment.class::isInstance);
        if (comment == Doc.EMPTY) {
            return Optional.empty();
        }
        return comment instanceof Doc.Text text && !text.value().isEmpty()
            ? Optional.of(text.value())
            : Optional.empty();
    }
}
