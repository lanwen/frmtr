package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.Function;

/**
 * Recovers the block comment that trails the last ordinary parameter of a callable — the {@code /* note *}{@code /}
 * after the final parameter and before the parameter list's closing {@code ")"}.
 *
 * <p>JavaParser frequently leaves such a comment unattached at callable scope rather than on the {@link Parameter}, so
 * this locates the closing paren from the callable's token range ({@link #closeParenForParameterList}) and accepts only
 * comments in the window after the last parameter ends and before that {@code ")"} ({@link #precedesCloseParen}).
 * Selecting by source order (not same-line) keeps the comment owned by the parameter under a collapsed/expanded layout,
 * while bounding strictly at {@code ")"} (never the body) keeps the recovery from stealing a following member's leading
 * comment (PR #20's narrowing).
 *
 * <p>The boundary lets the parameter renderer ask one authority for the trailing-comment text
 * ({@link #parameterTrailingBlockCommentText}) instead of carrying the close-paren token scan and window checks inline.
 * It claims through the injected {@link CommentTracker} and renders text through the injected producers; it leaves every
 * other parameter/type/prefix layout decision, and where the text is spliced in, to the caller.
 */
final class ParameterTrailingBlockCommentLayout {

    private final CommentTracker comments;

    private final Function<Doc, String> commentText;

    private final Function<Node, Doc> unattachedTrailingBlockComment;

    ParameterTrailingBlockCommentLayout(
            CommentTracker comments,
            Function<Doc, String> commentText,
            Function<Node, Doc> unattachedTrailingBlockComment
    ) {
        this.comments = comments;
        this.commentText = commentText;
        this.unattachedTrailingBlockComment = unattachedTrailingBlockComment;
    }

    String parameterTrailingBlockCommentText(Parameter parameter) {
        // A trailing block comment is the last parameter's only when it precedes the closing ")". A collapsed layout can
        // slide the next member's leading block up onto the last parameter's line, where a same-line recovery would reach
        // across ")" and claim it; requiring it to precede ")" keeps it with its real owner. At default it is on its own
        // line and matches nothing.
        if (!trailingBlockCommentPrecedesCloseParen(parameter)) {
            return "";
        }
        Doc trailingBlockComment = unattachedTrailingBlockComment.apply(parameter);
        if (trailingBlockComment == Doc.EMPTY) {
            trailingBlockComment = parameterTrailingBlockComment(parameter);
        }
        if (trailingBlockComment != Doc.EMPTY) {
            return " " + commentText.apply(trailingBlockComment);
        }
        return "";
    }

    /**
     * Reports whether a block comment trailing {@code parameter} lies in the source-order gap between the last
     * parameter's end and the parameter list's closing {@code ")"}, which is the only span from which it can genuinely
     * belong to the last parameter.
     *
     * <p>By source order, not same-line: a {@code param /* note *​/)} comment and an expanded layout that keeps it before
     * {@code ")"} both qualify, while bounding strictly at {@code ")"} (never the body) preserves PR #20's narrowing —
     * a following member's leading block a collapse slides onto the parameter's line begins after {@code ")"} and is
     * rejected. The closing paren is the first {@code RPAREN} at or after the parameter ends (a {@code ")"} from the
     * parameter's own annotation/type ends earlier and is skipped); a missing source range keeps the gate closed.
     */
    private boolean trailingBlockCommentPrecedesCloseParen(Parameter parameter) {
        Optional<Range> closeParenRange = closeParenForParameterList(parameter);
        if (closeParenRange.isEmpty()) {
            return false;
        }
        Range closeParen = closeParenRange.orElseThrow();
        return parameter.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .anyMatch(comment -> precedesCloseParen(parameter, comment, closeParen));
    }

    /**
     * Locates the parameter list's closing {@code ")"} for the last parameter, returning empty when {@code parameter} is
     * not the last ordinary parameter or any required source range is missing.
     */
    private Optional<Range> closeParenForParameterList(Parameter parameter) {
        if (!lastCallableParameter(parameter)) {
            return Optional.empty();
        }
        Optional<Range> parameterRange = parameter.getRange();
        if (parameterRange.isEmpty()) {
            return Optional.empty();
        }
        return parameter.getParentNode()
                .flatMap(Node::getTokenRange)
                .flatMap(tokenRange -> closeParenAfter(tokenRange, parameterRange.orElse(null)));
    }

    /**
     * Reports whether a block {@code comment} lies in the source-order window {@code (parameter end, closeParen)}: it
     * begins after the parameter's last token and before the parameter list's closing {@code ")"}. The lower bound is
     * source-order rather than same-line so a layout perturbation that moves the comment off the parameter's line cannot
     * defeat ownership; the upper bound stays at {@code ")"} so the recovery never reaches past the parameter list.
     */
    private boolean precedesCloseParen(Parameter parameter, Comment comment, Range closeParen) {
        return CommentIndex.startsAfterEndOf(parameter, comment) && CommentIndex.startsBefore(comment, closeParen.begin);
    }

    private Optional<Range> closeParenAfter(TokenRange tokenRange, Range parameterRange) {
        if (parameterRange == null) {
            return Optional.empty();
        }
        for (JavaToken token : tokenRange) {
            if (token.getKind() != GeneratedJavaParserConstants.RPAREN) {
                continue;
            }
            // The parameter list closer is the first ")" that begins strictly after the last parameter ends; any ")"
            // from a parameter annotation or type argument begins within the parameter's own span and is skipped.
            Optional<Range> range = token.getRange().filter(paren -> beginsAfter(paren.begin, parameterRange.end));
            if (range.isPresent()) {
                return range;
            }
        }
        return Optional.empty();
    }

    private static boolean beginsAfter(Position candidateBegin, Position parameterEnd) {
        if (candidateBegin.line != parameterEnd.line) {
            return candidateBegin.line > parameterEnd.line;
        }
        return candidateBegin.column > parameterEnd.column;
    }

    /**
     * Finds block comments that JavaParser leaves inside the callable rather than attaching to the last parameter node.
     *
     * <p>Same source-order window as the gate ({@link #trailingBlockCommentPrecedesCloseParen}): after the last parameter
     * ends and before the closing {@code ")"}. Source order (not same-line) keeps the comment owned by the parameter
     * under an expanded layout, and the {@code ")"} bound keeps the recovery out of a following member.
     */
    Doc parameterTrailingBlockComment(Parameter parameter) {
        Optional<Range> closeParenRange = closeParenForParameterList(parameter);
        if (closeParenRange.isEmpty()) {
            return Doc.EMPTY;
        }
        Range closeParen = closeParenRange.orElseThrow();
        return parameter.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .filter(comment -> precedesCloseParen(parameter, comment, closeParen))
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
    }

    /**
     * Checks whether trailing callable comments can be associated with this parameter.
     *
     * <p>Only the last ordinary parameter can own a same-line block comment that JavaParser left at callable scope; for
     * earlier parameters the comment would belong to a comma-separated gap instead.
     */
    boolean lastCallableParameter(Parameter parameter) {
        return parameter.getParentNode()
                .filter(CallableDeclaration.class::isInstance)
                .map(CallableDeclaration.class::cast)
                .map(declaration -> !declaration.getParameters().isEmpty()
                        && declaration.getParameters().get(declaration.getParameters().size() - 1) == parameter
                )
                .orElse(false);
    }
}
