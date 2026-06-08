package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tracks which JavaParser comments have already been consumed during one Java formatting run.
 *
 * <p>This helper owns stateful comment accounting: identity-based comment claims, leading and trailing attached comment
 * consumption, orphan-comment consumption, and the raw-preserved comment marks supplied by {@link RawPreservedSource}.
 * The boundary exists so comment consumption is centralized outside the parser/configuration entrypoint while printers
 * still share one "print once" state for a source file.
 *
 * <p>Callers still decide syntax-specific placement, spacing, ordering around neighboring nodes, and whether a raw
 * {@link Comment} predicate or classified {@link JavaCommentTrivia} predicate best describes the local layout rule.
 * Comment text rendering stays with {@link JavaFormatter#commentDoc(JavaCommentTrivia)} so this helper only accounts for
 * consumption and does not introduce new comment rendering policy.
 */
final class CommentTracker {
    private final Set<Comment> printed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Comment> rawRendered = Collections.newSetFromMap(new IdentityHashMap<>());

    Doc leading(Node node) {
        return node.getComment()
                .map(JavaCommentTrivia::from)
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                .orElse(Doc.EMPTY);
    }

    Doc trailingLineComment(Node node) {
        return node.getComment()
                .map(JavaCommentTrivia::from)
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsOnEndLine(node))
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    Doc orphanComments(Node node) {
        return orphanComments(node, ignored -> true);
    }

    Doc orphanComments(Node node, Predicate<Comment> predicate) {
        return Doc.concat(node.getOrphanComments().stream()
                .map(JavaCommentTrivia::from)
                .filter(trivia -> predicate.test(trivia.comment()))
                .filter(this::claim)
                .map(comment -> Doc.concat(JavaFormatter.commentDoc(comment), Doc.HARD_LINE))
                .toList());
    }

    List<Doc> orphanCommentStatements(Node node) {
        return orphanCommentStatements(node, ignored -> true);
    }

    List<Doc> orphanCommentStatements(Node node, Predicate<Comment> predicate) {
        return node.getOrphanComments().stream()
                .map(JavaCommentTrivia::from)
                .filter(trivia -> predicate.test(trivia.comment()))
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    List<Doc> orphanTriviaCommentStatements(Node node, Predicate<JavaCommentTrivia> predicate) {
        return node.getOrphanComments().stream()
                .map(JavaCommentTrivia::from)
                .filter(predicate)
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    Doc ownComment(Node node, Predicate<Comment> predicate) {
        return node.getComment()
                .map(JavaCommentTrivia::from)
                .filter(trivia -> predicate.test(trivia.comment()))
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    Doc ownTriviaComment(Node node, Predicate<JavaCommentTrivia> predicate) {
        return node.getComment()
                .map(JavaCommentTrivia::from)
                .filter(predicate)
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    Doc comment(Comment comment) {
        JavaCommentTrivia trivia = JavaCommentTrivia.from(comment);
        return claim(trivia) ? JavaFormatter.commentDoc(trivia) : Doc.EMPTY;
    }

    boolean isPrinted(JavaCommentTrivia trivia) {
        return trivia.isClaimedBy(printed);
    }

    /**
     * Records comments that {@link RawPreservedSource} intentionally preserved inside raw source text.
     *
     * <p>The canonical raw-preservation helper calls this while building the output {@link Doc}, keeping raw comment
     * accounting behind one output boundary instead of making each raw fallback remember a separate side effect.
     */
    void accountRaw(Node node) {
        FormatterGuardrails.accountRawComments(node, rawRendered);
    }

    /**
     * Records raw-rendered comments after the node's own attached comment has been printed separately.
     *
     * <p>{@link RawPreservedSource} uses this for fallbacks that already emitted the node's own comment through normal
     * attached-comment accounting. Nested and orphan comments inside the raw span remain represented only by the
     * recovered source text, so they are raw-accounted here without also counting the already printed own comment.
     */
    void accountRawWithoutOwnComment(Node node) {
        FormatterGuardrails.accountRawCommentsWithoutOwnComment(node, rawRendered);
    }

    /**
     * Fails in debug mode when JavaParser exposed a comment that was neither printed nor deliberately raw-preserved.
     *
     * <p>This is a development-only finalization check for one compilation-unit print. Normal formatter runs leave the
     * legacy best-effort behavior unchanged because {@link FormatterGuardrails#enabled()} controls whether any assertion
     * is evaluated.
     */
    void assertAllCommentsAccounted(CompilationUnit unit) {
        FormatterGuardrails.assertAllCommentsAccounted(unit, printed, rawRendered);
    }

    private boolean claim(JavaCommentTrivia trivia) {
        return FormatterGuardrails.claimComment(trivia, printed);
    }
}
