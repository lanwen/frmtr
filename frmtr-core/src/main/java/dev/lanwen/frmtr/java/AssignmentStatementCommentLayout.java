package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recovers the {@code //} line comments that trail an assignment statement's value around its terminating {@code ;} for
 * {@link AssignmentExpressionPrinter}.
 *
 * <p>This helper owns the two after-value comment cases whose comment JavaParser parks off the value's own trivia, so the
 * statement's ordinary value render would drop it: a method-call value's same-line tail line comment before the statement
 * {@code ;} (the {@code target = call(); // note} shape), and a binary value's after-final-operand comment deferred past
 * the {@code ;} as a {@link Doc#lineSuffix(Doc)} (the sibling of
 * {@code VariableInitializerLayout.preSemicolonInitializerComment}). Both resolve the enclosing {@link ExpressionStmt} as
 * the semicolon owner and select the comment purely by source position relative to that final {@code ;}.
 *
 * <p>The boundary exists so {@link AssignmentExpressionPrinter} can consult one authority — "does the value's tail carry a
 * comment the statement must re-emit, and which slot holds it?" — instead of carrying every candidate-set scan inline. The
 * helper reads the same candidate sets the caller renders from, so its presence verdict
 * ({@link #methodCallNeedsStatementTerminatorTail}) and the comment it recovers stay in lockstep. It never decides the
 * assignment's shape, whether the value breaks, or where the {@code ;} lands: the caller threads the presence predicate
 * into its statement-terminator gate and emits the {@link #assignmentValueTailLineComment} /
 * {@link #preSemicolonBinaryValueComment} slots around the value it renders.
 */
final class AssignmentStatementCommentLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    AssignmentStatementCommentLayout(CommentTracker comments, JavaCommentPlacementPolicy commentPlacement) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
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
    Doc preSemicolonBinaryValueComment(AssignExpr expression) {
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

    boolean methodCallNeedsStatementTerminatorTail(AssignExpr expression, MethodCallExpr methodCall) {
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

    Optional<JavaCommentTrivia> assignmentValueTailLineComment(
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
}
