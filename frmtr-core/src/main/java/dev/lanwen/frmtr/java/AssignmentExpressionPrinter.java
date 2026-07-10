package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
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

    private final JavaCommentPlacementPolicy commentPlacement;

    private final Function<Expression, Doc> expression;

    private final Function<Expression, Doc> expressionWithoutOwnComment;

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

    AssignmentExpressionPrinter(
            FormatterOptions options,
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            Function<Expression, Doc> expression,
            Function<Expression, Doc> expressionWithoutOwnComment,
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
        this.commentPlacement = commentPlacement;
        this.expression = expression;
        this.expressionWithoutOwnComment = expressionWithoutOwnComment;
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
            && methodCallNeedsStatementTerminatorTail(expression, methodCall)
        ) {
            return Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " "),
                expressionWithTail.render(methodCall, ExpressionTail.SEMICOLON, LayoutWidth.LineBudget.BLOCK),
                // When the assigned value is a method chain, the chain render above already claims and emits its own
                // final trailing line comment. Re-offering that same comment here only ever rendered empty, so skip it
                // when already printed to avoid a duplicate claim; output is unchanged because the chain render placed it.
                assignmentValueTailLineComment(expression, methodCall)
                        .filter(trivia -> !comments.isPrinted(trivia))
                        .map(comments::comment)
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
        Doc preSemicolonBinaryComment = preSemicolonBinaryValueComment(expression);
        return Doc.concat(assignment(expression), Doc.text(";"), preSemicolonBinaryComment);
    }

    /**
     * Recovers the {@code //} line comment that trails a binary assignment value after its final operand and before the
     * closing {@code ;}, deferring it past the {@code ;} as a {@link Doc#lineSuffix(Doc)} trailing comment.
     *
     * <p>This is the assignment-statement sibling of {@code VariableInitializerLayout.preSemicolonInitializerComment}.
     * When a binary assignment value wraps one operator per line, the comment-free broken-binary render
     * ({@code binaryExpressionLines}) emits operands only and never the comment JavaParser attaches to the final operand,
     * while the statement-level trailing-comment slot only sees a comment attached to the {@link AssignExpr}/
     * {@link ExpressionStmt} itself; the after-final-operand comment is therefore dropped both ways. We recover it through
     * the shared {@link CommentTracker#trailingInitializerCommentsBeforeSemicolon(Node, Node)} query — the same bucket the
     * variable/field initializer tail already uses — keyed on the enclosing {@link ExpressionStmt} as the semicolon owner
     * and the assignment value as the initializer.
     *
     * <p>The recovered comment is emitted as a {@link Doc#lineSuffix(Doc)} after the {@code ;} so it trails on whichever
     * line the {@code ;} lands on. Unlike the initializer tail, which drops the comment onto its own line above a bare
     * {@code ;}, the line-suffix form keeps the comment attached to the statement's final line whether the binary value
     * stays flat or breaks one operator per line. That keeps the render idempotent: a flat assignment whose binary value
     * does not actually wrap (so the comment-free render already fit) re-emits {@code ... value; // note} on a second pass
     * instead of pinning the comment onto a standalone line above a lone {@code ;} that the next pass would re-collapse.
     *
     * <p>The gate is intentionally narrow: only a {@link BinaryExpr} value can reach the comment-free broken-binary render
     * that drops the comment, so non-binary values keep their existing terminator byte-for-byte. When the statement-level
     * trailing slot already claimed the comment (the {@code value op = note} same-line shape) the claim-once query returns
     * an empty list, so the result is {@link Doc#EMPTY} and the assignment statement renders exactly as before.
     */
    private Doc preSemicolonBinaryValueComment(AssignExpr expression) {
        if (!(expression.getValue() instanceof BinaryExpr binaryValue)) {
            return Doc.EMPTY;
        }
        Node owner = semicolonOwner(expression).orElse(null);
        if (owner == null) {
            return Doc.EMPTY;
        }
        List<Doc> recovered = comments.trailingInitializerCommentsBeforeSemicolon(owner, binaryValue);
        if (recovered.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.lineSuffix(Doc.concat(Doc.text(" "), Doc.join(Doc.text(" "), recovered)));
    }

    private boolean methodCallNeedsStatementTerminatorTail(AssignExpr expression, MethodCallExpr methodCall) {
        return !methodCallFinalTrailingLineComments(methodCall).isEmpty()
            || assignmentValueTailLineComment(expression, methodCall).isPresent();
    }

    private List<JavaCommentTrivia> methodCallFinalTrailingLineComments(MethodCallExpr expression) {
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

    private Optional<JavaCommentTrivia> assignmentValueTailLineComment(
            AssignExpr expression,
            MethodCallExpr methodCall
    ) {
        return assignmentValueTailLineCommentCandidates(expression)
                .stream()
                .filter(comment -> comment.startsAfterNodeOnSameLine(methodCall))
                .filter(comment -> commentStartsBeforeStatementSemicolon(comment, expression))
                .findFirst();
    }

    private List<JavaCommentTrivia> assignmentValueTailLineCommentCandidates(AssignExpr expression) {
        List<JavaCommentTrivia> candidates = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(candidates::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> candidates.stream().noneMatch(existing -> existing.comment() == comment.comment()))
                .forEach(candidates::add);
        return candidates;
    }

    private boolean commentStartsBeforeStatementSemicolon(JavaCommentTrivia comment, AssignExpr expression) {
        return semicolonOwner(expression)
                .map(owner -> commentStartsBeforeFinalSemicolonInRawOwner(comment, owner))
                .orElse(false);
    }

    private boolean commentStartsBeforeFinalSemicolonInRawOwner(JavaCommentTrivia comment, Node owner) {
        String rawOwner = owner.getTokenRange().map(Object::toString).orElseGet(owner::toString);
        int commentIndex = commentIndex(rawOwner, comment);
        int semicolonIndex = rawOwner.lastIndexOf(';');
        return commentIndex >= 0 && semicolonIndex >= 0 && commentIndex < semicolonIndex;
    }

    private int commentIndex(String rawOwner, JavaCommentTrivia comment) {
        List<String> spellings = List.of(
            comment.comment().toString(),
            "//" + comment.comment().getContent(),
            "// " + comment.comment().getContent()
        );
        return spellings.stream()
                .mapToInt(rawOwner::indexOf)
                .filter(index -> index >= 0)
                .findFirst()
                .orElse(-1);
    }

    private Optional<Node> semicolonOwner(AssignExpr expression) {
        Node current = expression;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().orElseThrow();
            if (current instanceof ExpressionStmt) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    /**
     * Tries the width-triggered assignment branches in order of local layout-constraint strength.
     *
     * <p>Earlier branches handle shapes that have stronger local layout constraints: enclosed suffixes must stay attached
     * to their broken receiver, binary expressions have operator-position rules, constructor calls can break their
     * argument list, and ternaries/method calls have dedicated assignment hooks. Only when none of those cases applies
     * does the caller fall back to flat assignment assembly.
     */
    private Optional<Doc> brokenAssignment(AssignExpr expression) {
        Optional<Doc> suffixedEnclosedValue = assignmentWithSuffixedEnclosedValue(expression);
        if (suffixedEnclosedValue.isPresent()) {
            return suffixedEnclosedValue;
        }
        Optional<Doc> binaryValue = assignmentWithBinaryValue(expression);
        if (binaryValue.isPresent()) {
            return binaryValue;
        }
        Optional<Doc> objectCreationValue = assignmentWithObjectCreationValue(expression);
        if (objectCreationValue.isPresent()) {
            return objectCreationValue;
        }
        Optional<Doc> methodCallValue = assignmentWithMethodCallValue(expression);
        if (methodCallValue.isPresent()) {
            return methodCallValue;
        }
        Optional<Doc> conditionalValue = assignmentWithConditionalValue(expression);
        if (conditionalValue.isPresent()) {
            return conditionalValue;
        }
        return assignmentWithNestedAssignmentValue(expression);
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
        // that positional fact rides on the LayoutContext (#189) rather than a loose boolean argument.
        Optional<Doc> suffixedEnclosedValue =
            suffixedEnclosedExpression.apply(expression.getValue(), LayoutContext.root().withLeadingBreak(true));
        return suffixedEnclosedValue.map(value -> Doc.concat(
                this.expression.apply(expression.getTarget()),
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
                    this.expression.apply(expression.getTarget()),
                    Doc.text(" " + expression.getOperator().asString()),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, this.expression.apply(binaryExpression)))
                )
            );
        }
        return Optional.of(
            Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines.apply(expression.getValue(), true)))
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
        Doc target = this.expression.apply(expression.getTarget());
        String operator = expression.getOperator().asString();
        Doc lines = binaryExpressionLinesWithComments.apply(binaryValue);
        if (lineCommentedBinaryCanKeepFirstOperandWithOperator(expression, binaryValue)) {
            return Doc.concat(target, Doc.text(" " + operator + " "), Doc.indent(lines));
        }
        return Doc.concat(target, Doc.text(" " + operator), Doc.indent(Doc.concat(Doc.HARD_LINE, lines)));
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
                this.expression.apply(expression.getTarget()),
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
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, this.expression.apply(nestedAssignment)))
            )
        );
    }

    private Doc flatAssignment(AssignExpr expression) {
        Optional<String> gapBlockComment = gapBlockComment(expression);
        if (gapBlockComment.isPresent()) {
            return Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " " + gapBlockComment.orElseThrow() + " "),
                expressionWithoutOwnComment.apply(expression.getValue())
            );
        }
        return Doc.concat(
            this.expression.apply(expression.getTarget()),
            Doc.text(" " + expression.getOperator().asString() + " "),
            this.expression.apply(expression.getValue())
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
            this.expression.apply(expression.getTarget()),
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
        return this.expression.apply(value);
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
        String raw = expression.getTokenRange().map(Object::toString).orElseGet(expression::toString);
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
