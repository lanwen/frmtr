package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves comments that visually belong to enum constants before the enum printer chooses separators.
 *
 * <p>This helper owns enum-constant comment ownership: leading comments attached to a constant, same-line trailing
 * comments attached to the constant or its neighbor, contained comments on the constant's end line, and parent orphan
 * comments that should trail a constant instead of moving into the enum body. It intentionally does not decide enum
 * entry ordering, blank-line preservation, or raw recovery; callers use the returned {@link Tail} values for those
 * enum-list decisions. A resolved tail is rendered as a {@link Tail#suffix()} (a deferred {@link Doc#lineSuffix(Doc)}),
 * so the enum printer can emit separators and the list terminator unconditionally without the comment swallowing them.
 */
final class EnumConstantComments {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    EnumConstantComments(CommentTracker comments, JavaCommentPlacementPolicy commentPlacement) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
    }

    /**
     * Resolves each constant's trailing comment once so rendering and separator ownership share one source decision.
     */
    List<Tail> tails(EnumDeclaration owner) {
        List<EnumConstantDeclaration> entries = owner.getEntries();
        List<Tail> tails = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            EnumConstantDeclaration next = i + 1 < entries.size() ? entries.get(i + 1) : null;
            tails.add(tail(owner, entries.get(i), next));
        }
        return tails;
    }

    /**
     * Returns the leading comment only when it starts before the constant rather than trailing another constant.
     */
    Doc leading(EnumConstantDeclaration declaration) {
        Doc leading = comments.ownComment(
            declaration,
            comment -> CommentIndex.startsBeforeBeginLine(comment, declaration)
        );
        return leading == Doc.EMPTY ? Doc.EMPTY : Doc.concat(leading, Doc.HARD_LINE);
    }

    /**
     * Returns the first constant's leading comment, recovering the source-order line comments that {@code owner} owns
     * before the first constant in addition to the constant's own leading comment.
     *
     * <p>At {@code @default} JavaParser attaches a leading line comment ({@code // note}) above the first constant as that
     * constant's own comment, so {@link #leading(EnumConstantDeclaration)} alone renders it and the recovery adds nothing
     * (the union deduplicates by JavaParser comment identity before claiming). A whitespace perturbation that collapses
     * the enum body re-buckets the same comment onto the first constant's name {@link com.github.javaparser.ast.expr.SimpleName},
     * which the constant's own-comment view no longer exposes; {@link JavaCommentPlacementPolicy#lineCommentsBeforeFirst(Node,
     * Node)} keeps it owned by the enum entry list by selecting the line comments after the body brace and before the
     * first constant. Each recovered comment renders on its own line, exactly like the {@code @default} leading shape.
     */
    Doc firstConstantLeading(EnumDeclaration owner, EnumConstantDeclaration first) {
        Doc own = leading(first);
        List<Doc> recovered = commentPlacement.lineCommentsBeforeFirst(owner, first)
                .stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                .toList();
        if (recovered.isEmpty()) {
            return own;
        }
        return Doc.concat(Doc.concat(recovered), own);
    }

    /**
     * Finds comments written after a constant but attached by JavaParser to either the next constant or the enum body.
     */
    Tail tail(
            EnumDeclaration owner,
            EnumConstantDeclaration declaration,
            EnumConstantDeclaration next
    ) {
        Doc ownTrailing = declaration.getComment()
                .filter(comment -> trailsConstant(declaration, comment, next))
                .map(comments::comment)
                .orElse(Doc.EMPTY);
        if (ownTrailing != Doc.EMPTY) {
            return new Tail(ownTrailing);
        }
        Doc containedTrailing = Doc.concat(
            declaration.getAllContainedComments()
                    .stream()
                    .filter(comment -> CommentIndex.startsOnEndLine(declaration, comment))
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .toList()
        );
        if (containedTrailing != Doc.EMPTY) {
            return new Tail(containedTrailing);
        }
        Doc rangeTrailing = rangeTrailing(owner, declaration, next);
        if (rangeTrailing != Doc.EMPTY) {
            return new Tail(rangeTrailing);
        }
        if (next != null) {
            Doc nextTrailing = next.getComment()
                    .filter(comment -> trailsConstant(declaration, comment, next))
                    .map(comments::comment)
                    .orElse(Doc.EMPTY);
            if (nextTrailing != Doc.EMPTY) {
                return new Tail(nextTrailing);
            }
        }
        if (owner == null) {
            return Tail.EMPTY;
        }
        return new Tail(
            Doc.concat(
                comments.orphanCommentStatements(owner, comment -> CommentIndex.startsOnEndLine(declaration, comment))
            )
        );
    }

    private Doc rangeTrailing(
            EnumDeclaration owner,
            EnumConstantDeclaration declaration,
            EnumConstantDeclaration next
    ) {
        if (owner == null || next == null) {
            return Doc.EMPTY;
        }
        return Doc.concat(
            commentPlacement.lineCommentsBetween(owner, declaration, next)
                    .stream()
                    .filter(comment -> comment.startsOnEndLine(declaration))
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .toList()
        );
    }

    private static boolean trailsConstant(
            EnumConstantDeclaration declaration,
            Comment comment,
            EnumConstantDeclaration next
    ) {
        if (!CommentIndex.startsAfterNodeOnSameLine(declaration, comment)) {
            return false;
        }
        return next == null || !CommentIndex.startsAfterNodeOnSameLine(next, comment);
    }

    record Tail(Doc comment) {
        private static final Tail EMPTY = new Tail(Doc.EMPTY);

        boolean hasComment() {
            return comment != Doc.EMPTY;
        }

        /**
         * Returns the trailing comment as a {@link Doc#lineSuffix(Doc)} so it defers past the constant's separator and
         * flushes at the line break. Deferring it this way means the separator (and the list terminator on the last
         * constant) is always emitted unconditionally and cannot be commented out, regardless of which of the four
         * comment sources the tail came from.
         */
        Doc suffix() {
            return hasComment() ? Doc.lineSuffix(Doc.concat(Doc.text(" "), comment)) : Doc.EMPTY;
        }
    }
}
