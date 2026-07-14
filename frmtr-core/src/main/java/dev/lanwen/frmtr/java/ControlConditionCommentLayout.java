package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Owns comment placement for expressions rendered inside a parenthesized control condition — the family of own-comment,
 * trailing-line-comment, close-paren-tail, and attached-block-comment slots that keep a condition's source comment shape.
 *
 * <p>This helper hosts the renderers that emit a condition's directly attached comments (a line comment written before or
 * after the condition value, a block comment folded into the compact text) together with the raw-source recovery that
 * finds a {@code cond // note} or {@code ) // note} trailing line comment JavaParser left in the gap after the condition
 * rather than attaching to it. The boundary exists so {@link ControlConditionPrinter} can ask one authority "does this
 * condition carry a comment, and how is it re-emitted?" without carrying every comment scan and raw-source slice inline.
 *
 * <p>The helper claims no ownership of the condition's width gate, its broken operand-by-operand layout, or the
 * logical/detached-line-comment family that forces structured rendering: it renders the attached and raw-recovered
 * comments it is asked for and reports whether a line comment trails the condition content, but never decides a
 * condition's shape. That stays with {@link ControlConditionPrinter}, which threads these slots into its dispatch and its
 * broken-condition render.
 */
final class ControlConditionCommentLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceText sourceText;

    private final Function<Expression, String> compact;

    private final Function<Expression, String> compactWithoutOwnComment;

    ControlConditionCommentLayout(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            SourceText sourceText,
            Function<Expression, String> compact,
            Function<Expression, String> compactWithoutOwnComment
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceText = sourceText;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
    }

    Optional<Doc> commentedCondition(Expression condition) {
        Optional<Doc> lineComment = lineCommentCondition(condition);
        if (lineComment.isPresent()) {
            return lineComment;
        }
        if (condition.getComment().filter(BlockComment.class::isInstance).isPresent()) {
            Comment comment = condition.getComment().orElseThrow();
            String text = commentText(comments.comment(comment));
            String expressionText = compactWithoutOwnComment.apply(condition);
            String conditionText = conditionCommentStartsBeforeExpression(condition, comment)
                ? text + " " + expressionText
                : expressionText + " " + text;
            return Optional.of(Doc.text("(" + conditionText + ")"));
        }
        Doc trailingBlock = trailingBlockCommentBeforeCloseParen(condition);
        if (trailingBlock != Doc.EMPTY) {
            return Optional.of(Doc.text("(" + compact.apply(condition) + " " + commentText(trailingBlock) + ")"));
        }
        return Optional.empty();
    }

    Optional<Doc> lineCommentCondition(Expression condition) {
        Optional<Doc> ownLineComment = ownLineCommentCondition(condition);
        if (ownLineComment.isPresent()) {
            return ownLineComment;
        }
        return recoverableTrailingLineCommentCondition(condition);
    }

    private Optional<Doc> ownLineCommentCondition(Expression condition) {
        Optional<Comment> ownComment = condition.getComment();
        if (ownComment.filter(LineComment.class::isInstance).isPresent()) {
            Comment comment = ownComment.orElseThrow();
            if (
                !conditionCommentStartsBeforeExpression(condition, comment)
                && !lineCommentTrailsInsideCondition(condition, comment)
            ) {
                return Optional.empty();
            }
            Doc printedComment = comments.comment(comment);
            Doc conditionDoc = conditionCommentStartsBeforeExpression(condition, comment)
                ? Doc.join(Doc.HARD_LINE, List.of(printedComment, Doc.text(compactWithoutOwnComment.apply(condition))))
                : Doc.concat(
                    Doc.text(compactWithoutOwnComment.apply(condition)),
                    Doc.lineSuffix(Doc.concat(Doc.text(" "), printedComment))
                );
            return Optional.of(
                Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, conditionDoc)),
                    Doc.HARD_LINE,
                    Doc.text(")")
                )
            );
        }
        return Optional.empty();
    }

    /**
     * Recovers trailing line comments that JavaParser leaves on the parent or only in the raw source gap instead of
     * attaching to the condition.
     *
     * <p>Recovered switch entry lists can keep the selector expression parsed while storing {@code value // comment}
     * trivia outside the selector's own comment association. The shared condition renderer still owns that source
     * shape, so it asks the placement policy first, then falls back to a narrow source slice before choosing a compact
     * form.
     */
    private Optional<Doc> recoverableTrailingLineCommentCondition(Expression condition) {
        Optional<String> rawComment = rawTrailingLineCommentText(condition);
        if (rawComment.isEmpty()) {
            return Optional.empty();
        }
        Optional<JavaCommentTrivia> trailing = commentPlacement.sameLineTrailingLineComment(condition)
                .filter(comment -> lineCommentTrailsConditionContent(condition, comment.comment()))
                .filter(comment -> rawComment.orElseThrow().equals(commentText(JavaFormatter.commentDoc(comment))));
        if (trailing.isPresent()) {
            Doc printedComment = comments.comment(trailing.orElseThrow());
            if (printedComment == Doc.EMPTY) {
                return Optional.empty();
            }
            return Optional.of(conditionWithTrailingLineComment(condition, printedComment));
        }
        return Optional.of(conditionWithTrailingLineComment(condition, Doc.text(rawComment.orElseThrow())));
    }

    Doc closeParenTrailingLineComment(Expression condition) {
        Optional<String> rawComment = rawCloseParenTrailingLineCommentText(condition);
        if (rawComment.isEmpty()) {
            return recoveredCloseParenTrailingLineComment(condition);
        }
        Optional<JavaCommentTrivia> trailing = commentPlacement.sameLineTrailingLineComment(condition)
                .filter(comment -> lineCommentTrailsConditionContent(condition, comment.comment()))
                .filter(comment -> rawComment.orElseThrow().equals(commentText(JavaFormatter.commentDoc(comment))));
        if (trailing.isPresent()) {
            Doc printedComment = comments.comment(trailing.orElseThrow());
            return printedComment == Doc.EMPTY ? Doc.EMPTY : printedComment;
        }
        return Doc.text(rawComment.orElseThrow());
    }

    /**
     * Recovers the close-paren trailing line comment of an {@code if} statement that a whitespace perturbation moved onto
     * its own line below the {@code )} and re-bucketed onto the enclosing {@link IfStmt} as an orphan, where the inline
     * raw-slice path no longer finds it.
     *
     * <p>At {@code @default} the comment sits inline after {@code )} and the raw-slice path renders it, so this fires only
     * under perturbation. The value comes from the orphan comment itself, not a raw slice (there is no inline slice to
     * match when it is on its own line). Scoped to {@link IfStmt}, the only construct with a distinct then-statement node
     * bounding the {@code )}-to-body gap; a switch selector's following comment is a switch-body leading comment
     * {@code SwitchPrinter} owns, not a close-paren tail.
     */
    private Doc recoveredCloseParenTrailingLineComment(Expression condition) {
        return closeParenTrailingOrphans(condition)
                .stream()
                .findFirst()
                .map(comments::comment)
                .filter(printed -> printed != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    private List<JavaCommentTrivia> closeParenTrailingOrphans(Expression condition) {
        Optional<IfStmt> controlStmt = condition.getParentNode()
                .filter(IfStmt.class::isInstance)
                .map(IfStmt.class::cast);
        if (controlStmt.isEmpty()) {
            return List.of();
        }
        return commentPlacement.trailingConditionComments(
            controlStmt.orElseThrow(),
            condition,
            controlStmt.orElseThrow().getThenStmt()
        );
    }

    private boolean lineCommentTrailsInsideCondition(Expression condition, Comment comment) {
        return lineCommentTrailsConditionContent(condition, comment)
            && rawTrailingLineCommentText(condition)
                    .filter(rawComment -> rawComment.equals(commentText(JavaFormatter.commentDoc(comment))))
                    .isPresent();
    }

    /**
     * Lays the condition on its own line inside the parentheses and defers the trailing line comment to a {@link
     * Doc#lineSuffix(Doc)} so it flushes at the line break before the closing paren. Emitting the comment as a suffix
     * rather than baking it into the condition's text literal keeps the comment out of width measurement (a trailing
     * comment never widens the line it sits on) while rendering byte-identically to {@code condition // comment}.
     */
    private Doc conditionWithTrailingLineComment(Expression condition, Doc comment) {
        Doc conditionDoc = Doc.concat(
            Doc.text(compact.apply(condition)),
            Doc.lineSuffix(Doc.concat(Doc.text(" "), comment))
        );
        return Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, conditionDoc)),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Optional<String> rawTrailingLineCommentText(Expression condition) {
        Optional<String> suffix = condition.getRange()
                .flatMap(conditionRange -> condition.getParentNode()
                            .flatMap(Node::getRange)
                            .map(parentRange -> sourceText.sliceAfterWithin(conditionRange, parentRange))
                );
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        String text = suffix.orElseThrow();
        int closeParen = text.indexOf(')');
        int commentStart = text.indexOf("//");
        if (
            closeParen < 0
            || commentStart < 0
            || closeParen < commentStart
            || !onlyHorizontalWhitespace(text, 0, commentStart)
        ) {
            return Optional.empty();
        }
        int commentEnd = lineEnd(text, commentStart);
        return Optional.of(stripTrailingHorizontalWhitespace(text.substring(commentStart, commentEnd)));
    }

    private Optional<String> rawCloseParenTrailingLineCommentText(Expression condition) {
        Optional<String> suffix = condition.getRange()
                .flatMap(conditionRange -> condition.getParentNode()
                            .flatMap(Node::getRange)
                            .map(parentRange -> sourceText.sliceAfterWithin(conditionRange, parentRange))
                );
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        String text = suffix.orElseThrow();
        int closeParen = text.indexOf(')');
        int commentStart = text.indexOf("//");
        if (
            closeParen < 0
            || commentStart < 0
            || commentStart < closeParen
            || !onlyHorizontalWhitespace(text, closeParen + 1, commentStart)
        ) {
            return Optional.empty();
        }
        int commentEnd = lineEnd(text, commentStart);
        return Optional.of(stripTrailingHorizontalWhitespace(text.substring(commentStart, commentEnd)));
    }

    private boolean onlyHorizontalWhitespace(String text, int begin, int end) {
        for (int index = begin; index < end; index++) {
            char value = text.charAt(index);
            if (value != ' ' && value != '\t' && value != '\f') {
                return false;
            }
        }
        return true;
    }

    private int lineEnd(String text, int begin) {
        int lineFeed = text.indexOf('\n', begin);
        int carriageReturn = text.indexOf('\r', begin);
        if (lineFeed < 0) {
            return carriageReturn < 0 ? text.length() : carriageReturn;
        }
        if (carriageReturn < 0) {
            return lineFeed;
        }
        return Math.min(lineFeed, carriageReturn);
    }

    private String stripTrailingHorizontalWhitespace(String text) {
        int end = text.length();
        while (end > 0) {
            char value = text.charAt(end - 1);
            if (value != ' ' && value != '\t' && value != '\f') {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    private Doc trailingBlockCommentBeforeCloseParen(Expression condition) {
        return condition.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .filter(comment -> comment.getCommentedNode().map(BlockStmt.class::isInstance).orElse(false))
                .filter(comment -> CommentIndex.startsImmediatelyAfterNodeOnSameLine(condition, comment))
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
    }

    /**
     * Builds compact condition text while preserving where an attached block comment appeared in source.
     *
     * <p>JavaParser attaches both block comments before a condition value and block comments after that value as an own
     * comment on the expression. A normal compact clone would erase that distinction, so this fork compares source
     * ranges and places the comment before or after the expression text to keep that visible condition shape.
     */
    String compactWithOwnBlockComment(Expression expression) {
        Optional<Comment> ownComment = expression.getComment().filter(BlockComment.class::isInstance);
        if (ownComment.isEmpty()) {
            return compact.apply(expression);
        }
        Comment comment = ownComment.orElseThrow();
        String commentText = commentText(comments.comment(comment));
        String expressionText = compactWithoutOwnComment.apply(expression);
        return conditionCommentStartsBeforeExpression(expression, comment)
            ? commentText + " " + expressionText
            : expressionText + " " + commentText;
    }

    private boolean conditionCommentStartsBeforeExpression(Expression condition, Comment comment) {
        return CommentIndex.startsBefore(comment, condition);
    }

    boolean lineCommentTrailsConditionContent(Expression condition, Comment comment) {
        return condition.getRange()
                .flatMap(conditionRange -> comment.getRange().map(
                        commentRange -> commentRange.begin.line == conditionRange.begin.line
                                && commentRange.begin.column > conditionRange.begin.column
                ))
                .orElse(false);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }
}
