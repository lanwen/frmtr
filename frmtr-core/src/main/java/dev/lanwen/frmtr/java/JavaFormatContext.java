package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import dev.lanwen.frmtr.FormatterOptions;

/**
 * Carries formatter-wide state and source helpers that every Java printer should share for one formatting run.
 *
 * <p>The context owns dependencies whose identity matters across helper boundaries: options, printed-comment tracking,
 * formatter pragma state, raw source recovery, raw-preserved source output, compact source text, the per-run comment
 * placement policy, and source-position comment placement. The boundary exists so {@link JavaPrinter} can remain the
 * composition root without threading the same shared collaborators through every helper constructor separately.
 *
 * <p>Helpers should still keep syntax-specific callbacks and layout decisions in their own constructors. This context
 * is not a service locator for printers, dispatchers, or renderer policy; it only provides the common stateful
 * formatter dependencies for the current Java source file.
 */
final class JavaFormatContext {
    final FormatterOptions options;
    final CommentTracker comments;
    final JavaCommentPlacementPolicy commentPlacementPolicy;
    final FormatterPragmas formatterPragmas;
    final RawSource rawSource;
    final RawPreservedSource rawPreservedSource;
    final SourceText sourceText;
    final SourceShape sourceShape;
    final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;
    final RecoveredListPlanner recoveredListPlanner;
    final RecoveredSourceRegions recoveredSourceRegions;
    final CompactSourceText compactSource;
    final LayoutWidth layoutWidth;
    final CommentPlacement commentPlacement;
    final boolean recoverParseProblems;

    JavaFormatContext(FormatterOptions options, SourceText sourceText, boolean recoverParseProblems) {
        this.options = options;
        this.sourceText = sourceText;
        this.recoverParseProblems = recoverParseProblems;
        this.commentPlacementPolicy = new JavaCommentPlacementPolicy();
        this.comments = new CommentTracker(commentPlacementPolicy);
        this.formatterPragmas = new FormatterPragmas();
        this.rawSource = new RawSource(options);
        this.sourceShape = new SourceShape(rawSource, sourceText);
        this.objectCreationLayoutPolicy = new ObjectCreationLayoutPolicy(sourceShape);
        this.rawPreservedSource = new RawPreservedSource(rawSource, comments);
        this.recoveredListPlanner = new RecoveredListPlanner(sourceText);
        this.recoveredSourceRegions = new RecoveredSourceRegions(sourceText, options, comments);
        this.compactSource = new CompactSourceText(rawSource);
        this.layoutWidth = new LayoutWidth(options);
        this.commentPlacement = new CommentPlacement(comments, commentPlacementPolicy);
    }

    /**
     * Builds comment placement state at the print boundary once the compilation unit is available.
     */
    void startCommentRun(CompilationUnit unit) {
        commentPlacementPolicy.startRun(unit);
    }
}
