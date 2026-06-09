package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Interleaves syntax siblings with orphan comments by original source position.
 *
 * <p>This helper owns the repeated source-order merge used by block-like printers when JavaParser exposes comments
 * separately from the sibling list they visually sit beside. The boundary exists so statement blocks and member blocks
 * share one explicit previous-entry model instead of each encoding comment placement with local sentinel line values.
 *
 * <p>Callers still decide which orphan comments are eligible, how individual siblings render, and which separator is
 * appropriate before comments or syntax siblings in that caller's grammar.
 */
final class SourceOrderedCommentInterleaver<T extends Node> {
    private final CommentTracker comments;

    SourceOrderedCommentInterleaver(CommentTracker comments) {
        this.comments = comments;
    }

    /**
     * Returns docs for {@code siblings} and {@code orphanComments}, preserving their source-line ordering.
     */
    List<Doc> interleave(
            List<T> siblings,
            List<JavaCommentTrivia> orphanComments,
            SiblingRenderer<T> siblingRenderer,
            Spacing<T> spacing) {
        List<JavaCommentTrivia> orderedComments = orphanComments.stream()
                .sorted(Comparator.comparingInt(comment -> comment.beginLine(Integer.MAX_VALUE)))
                .toList();
        List<Doc> contents = new ArrayList<>();
        PreviousEntry<T> previous = PreviousEntry.none();
        int orphanIndex = 0;
        Optional<T> previousSibling = Optional.empty();

        for (int index = 0; index < siblings.size(); index++) {
            T current = siblings.get(index);
            int currentBeginLine = spacing.beginLine(current);
            while (orphanIndex < orderedComments.size()
                    && orderedComments.get(orphanIndex).beginLine(Integer.MAX_VALUE) < currentBeginLine) {
                previous = appendComment(contents, orderedComments.get(orphanIndex++), previous, spacing);
            }
            Optional<Doc> siblingDoc = siblingRenderer.render(previousSibling, current, index);
            if (siblingDoc.isEmpty()) {
                continue;
            }
            if (previous.kind() != EntryKind.NONE) {
                contents.add(spacing.separatorBeforeSibling(previous, current));
            }
            contents.add(siblingDoc.orElseThrow());
            previous = PreviousEntry.sibling(current, spacing.endLine(current));
            previousSibling = Optional.of(current);
        }

        while (orphanIndex < orderedComments.size()) {
            previous = appendComment(contents, orderedComments.get(orphanIndex++), previous, spacing);
        }
        return contents;
    }

    private PreviousEntry<T> appendComment(
            List<Doc> contents,
            JavaCommentTrivia comment,
            PreviousEntry<T> previous,
            Spacing<T> spacing) {
        Doc commentDoc = comments.comment(comment);
        if (commentDoc == Doc.EMPTY) {
            return previous;
        }
        if (previous.kind() != EntryKind.NONE) {
            contents.add(spacing.separatorBeforeComment(previous, comment));
        }
        contents.add(commentDoc);
        return PreviousEntry.comment(comment.endLine(comment.beginLine(previous.endLine())));
    }

    @FunctionalInterface
    interface SiblingRenderer<T extends Node> {
        Optional<Doc> render(Optional<T> previousSibling, T currentSibling, int siblingIndex);
    }

    interface Spacing<T extends Node> {
        int beginLine(T sibling);

        int endLine(T sibling);

        Doc separatorBeforeSibling(PreviousEntry<T> previous, T currentSibling);

        Doc separatorBeforeComment(PreviousEntry<T> previous, JavaCommentTrivia comment);
    }

    record PreviousEntry<T extends Node>(EntryKind kind, Optional<T> sibling, int endLine) {
        static <T extends Node> PreviousEntry<T> none() {
            return new PreviousEntry<>(EntryKind.NONE, Optional.empty(), Integer.MIN_VALUE);
        }

        static <T extends Node> PreviousEntry<T> sibling(T sibling, int endLine) {
            return new PreviousEntry<>(EntryKind.SIBLING, Optional.of(sibling), endLine);
        }

        static <T extends Node> PreviousEntry<T> comment(int endLine) {
            return new PreviousEntry<>(EntryKind.COMMENT, Optional.empty(), endLine);
        }
    }

    enum EntryKind {
        /** No printable sibling or comment has been emitted yet in the current interleaved list. */
        NONE,
        /** The previous emitted entry was a syntax sibling from the caller's ordered node list. */
        SIBLING,
        /** The previous emitted entry was an orphan comment restored between syntax siblings. */
        COMMENT,
    }
}
