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
 * Recovers the block comment that trails the last ordinary parameter of a callable — the {@code /* note *}{@code /} that
 * sits after the final parameter and before the parameter list's closing {@code ")"}.
 *
 * <p>This helper owns the "does a block comment belong to the last parameter, and what is its rendered text?" question
 * for {@link CallableSignaturePrinter}. JavaParser frequently leaves such a comment unattached at callable scope rather
 * than on the {@link Parameter} node, so the helper locates the parameter list's closing paren from the callable's token
 * range ({@link #closeParenForParameterList}) and accepts only comments whose source position falls in the window after
 * the last parameter ends and before that {@code ")"} ({@link #precedesCloseParen}). Selecting by source order rather than
 * by same-line keeps the comment owned by the parameter even when a collapsed or expanded whitespace layout slides it
 * off the parameter's line, while bounding strictly at {@code ")"} (never the method body) keeps the recovery from reaching
 * past the parameter list and stealing a following member's leading comment (PR #20's narrowing).
 *
 * <p>The boundary exists so the parameter renderer can ask one authority for the trailing-comment text
 * ({@link #parameterTrailingBlockCommentText}) instead of carrying the close-paren token scan and the source-order
 * window checks inline. The helper claims the recovered comment through the injected {@link CommentTracker} and turns
 * comments into text through the injected renderers (the {@code commentText} producer and the
 * unattached-trailing-block-comment producer); it leaves every other parameter, type, and prefix layout decision — and
 * where the returned text is spliced into a parameter's rendered doc — to the caller.
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
        // A trailing block comment is the last parameter's only when it sits inside the parameter list, i.e. before the
        // closing ")". A collapsed layout can slide the next member's leading block comment up onto the last parameter's
        // line, where the same-line recovery would otherwise reach across ")" (and even the method body) and claim a
        // comment that belongs to the following member. Requiring the comment to precede ")" keeps that comment with its
        // real owner. At the default layout the comment is on its own line, so the recovery already matches nothing.
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
     * <p>Selection is by source order, not by same-line: the comment must begin after the parameter ends and before the
     * close paren. A same-line {@code param /* note *​/)} block comment satisfies both bounds, and so does an expanded
     * layout that pushes the comment onto its own line while keeping it before {@code ")"} — the body block's own
     * comment that {@code expand} slides off the last parameter's line. Bounding strictly at {@code ")"} (never the body
     * brace) preserves PR #20's narrowing: under {@code collapse} a following member's leading block comment can slide
     * up onto the last parameter's line, but it begins after {@code ")"}, so it is rejected and stays with its real
     * owner.
     *
     * <p>The closing paren is the first {@code RPAREN} in the callable's token range that begins at or after the last
     * parameter ends; any {@code ")"} from an annotation or type on the parameter itself ends before the parameter does,
     * so it is skipped. When the callable, the parameter, or the close paren has no source range, the gate stays closed
     * and the recovery is suppressed rather than guessed.
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
     * <p>Selection is the same source-order window the gate ({@link #trailingBlockCommentPrecedesCloseParen}) uses: the
     * comment must begin after the last parameter ends and before the parameter list's closing {@code ")"}. Bounding by
     * source order rather than same-line keeps the comment owned by the parameter even when an expanded layout pushes it
     * onto its own line (the body block's own comment that {@code expand} slides off the parameter's line), while the
     * {@code ")"} upper bound keeps the recovery from reaching past the parameter list into a following member.
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
