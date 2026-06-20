package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Emits recovered source regions as labeled raw document islands and accounts comments inside those islands.
 *
 * <p>This helper owns the boundary between offset-based recovery regions and formatter output: it slices the original
 * source text, labels the resulting raw {@link Doc}, and records comments whose full source range is contained by the
 * recovered region. The boundary exists so later recovery planners can focus on selecting safe gaps without duplicating
 * raw slicing, debug-label formatting, or comment-accounting rules.
 *
 * <p>Callers still decide which source regions are safe recovery boundaries, which diagnostic kind names are useful, how
 * crossing comments should widen or fail a plan, and where recovered docs belong in surrounding syntax layout.
 */
final class RecoveredSourceRegions {

    private static final String LABEL_PREFIX = "java.recoveredRegion";

    private final SourceText sourceText;

    private final FormatterOptions options;

    private final CommentTracker comments;

    RecoveredSourceRegions(SourceText sourceText, FormatterOptions options, CommentTracker comments) {
        this.sourceText = Objects.requireNonNull(sourceText, "sourceText");
        this.options = Objects.requireNonNull(options, "options");
        this.comments = Objects.requireNonNull(comments, "comments");
    }

    /**
     * Emits a labeled raw source slice and raw-accounts comments fully contained by {@code region}.
     *
     * <p>The validation happens before comment accounting so a crossing comment cannot be partly claimed by a recovery
     * attempt that later needs to widen or fail. Comments outside the region are intentionally left untouched for normal
     * structured comment placement.
     */
    Doc raw(Node commentRoot, SourceRegion region, String diagnosticKind) {
        CommentAccounting accounting = commentAccounting(commentRoot, region);
        accounting.requireNoCrossing(region);
        String label = label(region, diagnosticKind);
        String rawText = sourceText.rawSlice(region, options);
        Doc doc = Doc.label(label, Doc.text(rawText));
        comments.accountRaw(accounting.containedComments());
        return doc;
    }

    /**
     * Classifies JavaParser comments by offset containment without mutating comment accounting.
     *
     * <p>Fully contained comments can safely be represented by the raw source slice. Comments that overlap the region
     * but are not fully contained cross a recovery boundary. Comments without source ranges are also unsafe because
     * recovery cannot prove containment. Planners must widen the region or reject the recovery plan before emitting any
     * raw doc.
     */
    CommentAccounting commentAccounting(Node commentRoot, SourceRegion region) {
        Objects.requireNonNull(commentRoot, "commentRoot");
        Objects.requireNonNull(region, "region");
        List<Comment> contained = new ArrayList<>();
        List<Comment> crossing = new ArrayList<>();
        commentRoot.getAllContainedComments()
                .stream()
                .sorted(CommentIndex.sourceOrderComparator())
                .forEach(comment -> {
                    if (comment.getRange().isEmpty()) {
                        crossing.add(comment);
                        return;
                    }
                    SourceRegion commentRegion = sourceText.region(comment.getRange().orElseThrow());
                    if (fullyContains(region, commentRegion)) {
                        contained.add(comment);
                    } else if (overlaps(region, commentRegion)) {
                        crossing.add(comment);
                    }
                });
        return new CommentAccounting(contained, crossing);
    }

    private static boolean fullyContains(SourceRegion region, SourceRegion comment) {
        return region.beginOffset() <= comment.beginOffset() && comment.endOffset() <= region.endOffset();
    }

    private static boolean overlaps(SourceRegion left, SourceRegion right) {
        return left.beginOffset() < right.endOffset() && right.beginOffset() < left.endOffset();
    }

    private static String label(SourceRegion region, String diagnosticKind) {
        String kind = Objects.requireNonNull(diagnosticKind, "diagnosticKind").strip();
        if (kind.isEmpty()) {
            throw new IllegalArgumentException("diagnosticKind must not be blank");
        }
        return "%s:%s@%d:%d-%d:%d".formatted(
            LABEL_PREFIX,
            kind,
            region.beginLine(),
            region.beginColumn(),
            region.endLine(),
            region.endColumn()
        );
    }

    /**
     * Describes the comment ownership result for one candidate recovered region.
     *
     * <p>This value owns no side effects. Future planners can inspect crossing comments before deciding whether to widen
     * a region, while the raw emitter can reuse the same result to fail fast and then account contained comments.
     */
    record CommentAccounting(List<Comment> containedComments, List<Comment> crossingComments) {
        CommentAccounting {
            containedComments = List.copyOf(containedComments);
            crossingComments = List.copyOf(crossingComments);
        }

        /**
         * Fails when any comment overlaps {@code region} without being fully contained by it, or when a comment has no
         * source range that recovery can use to prove containment.
         */
        void requireNoCrossing(SourceRegion region) {
            if (!crossingComments.isEmpty()) {
                throw new CrossingCommentBoundaryException(region, crossingComments);
            }
        }
    }

    /**
     * Reports that a recovered source region would split a JavaParser-visible comment.
     */
    static final class CrossingCommentBoundaryException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient SourceRegion region;

        private final transient List<Comment> crossingComments;

        CrossingCommentBoundaryException(SourceRegion region, List<Comment> crossingComments) {
            super(message(region, crossingComments));
            this.region = Objects.requireNonNull(region, "region");
            this.crossingComments = List.copyOf(crossingComments);
        }

        SourceRegion region() {
            return region;
        }

        List<Comment> crossingComments() {
            return crossingComments;
        }

        private static String message(SourceRegion region, List<Comment> crossingComments) {
            Comment first = crossingComments.getFirst();
            String range = first.getRange().map(Object::toString).orElse("unknown range");
            return "Recovered source region "
                + region.lineColumnLabel()
                + (first.getRange().isPresent() ? " crosses " : " cannot safely account ")
                + first.getClass().getSimpleName()
                + " at "
                + range;
        }
    }
}
