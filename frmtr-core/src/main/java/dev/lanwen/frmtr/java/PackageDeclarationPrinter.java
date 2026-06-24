package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;

/**
 * Prints package declarations after the compilation-unit ordering rules have selected the package position.
 *
 * <p>This helper owns the raw source-leading comment prefix that can appear before {@code package ...} and the package
 * declaration line with JavaParser-attributed leading comments. It intentionally delegates orphan-comment sequencing,
 * import block selection, module declarations, and top-level declaration dispatch back to {@link JavaPrinter} because
 * those are whole-compilation-unit layout decisions rather than package declaration formatting.
 *
 * <p>Representative fixture pairs live under
 * {@code frmtr-core/src/test/resources/format/package-imports-mixed-imports}. Source-leading package comments are
 * covered near {@code frmtr-core/src/test/resources/format/comment-preservation-class-members}.
 */
final class PackageDeclarationPrinter {

    private final CommentTracker comments;

    private final RawSource rawSource;

    private final FormatterOptions options;

    PackageDeclarationPrinter(CommentTracker comments, RawSource rawSource, FormatterOptions options) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
    }

    /**
     * Recovers raw comments that appear in the source before the package declaration token sequence.
     *
     * <p>This path only emits the raw prefix when a package declaration exists, the compilation-unit token stream has
     * content before the {@code package} keyword, and that earlier content starts with a line or block comment. If any
     * of those checks fail, the caller's normal orphan-comment and JavaParser leading-comment handling keeps ownership
     * exactly as before.
     *
     * <p>The package boundary is located by the lexer {@code package} keyword token, not by a literal
     * {@code "package <name>"} substring match. A substring match is defeated whenever the source has non-canonical
     * spacing inside the package token run (for example {@code package  com . google . common . collect}, as the
     * idempotence/comment-presence whitespace perturbations produce): the literal {@code package com.google...} prefix
     * no longer occurs verbatim, so the recovery would silently return {@link Doc#EMPTY} and drop an AST-invisible file
     * header. Accumulating the raw token text up to the first {@code package} keyword reconstructs exactly the same
     * leading region the old {@code rawUnit.substring(0, packageStart)} produced, independent of intra-declaration
     * spacing.
     *
     * <p>The raw sweep stops at the comment JavaParser attached to the package declaration as its leading trivia, not at
     * the {@code package} keyword itself, whenever such a comment exists. When two or more comments precede
     * {@code package} (for example a license header followed by a {@code /** package doc *}{@code /}, the
     * {@code package-info.java} shape), JavaParser attaches the <em>last</em> one as the package declaration's leading
     * comment while leaving the earlier ones detached. {@link #packageDeclaration(PackageDeclaration)} already emits that
     * attached comment through the tracked {@link CommentTracker#leading(com.github.javaparser.ast.Node)} slot, so
     * sweeping it into the raw prefix too would emit it twice — once raw (untracked, invisible to the strict-claims and
     * comment-presence guardrails because it never claims) and once tracked — duplicating the package doc on every pass
     * without bound. Truncating the sweep at the attached comment's begin position keeps each pre-{@code package} comment
     * emitted exactly once: the detached header(s) raw, the attached comment via the leading slot. Files with a single
     * pre-{@code package} comment have no attached package leading comment, so the sweep still runs to the {@code package}
     * keyword and their output is unchanged.
     *
     * <p>A multi-line run of {@code //} lines before {@code package} needs more than the attached-comment truncation
     * because JavaParser does not keep the run as one comment: it drops the first {@code //} line from the AST entirely
     * and retains each later line as a <em>compilation-unit orphan</em> (not attached to the package declaration). The
     * raw sweep already reconstructs the whole run verbatim from the token stream — that is the only path that carries the
     * dropped first line — but the retained orphans would <em>also</em> be emitted by {@link CompilationUnitPrinter}'s
     * before-first-type orphan slot, duplicating the last header line(s) and gluing the duplicate onto {@code package}.
     * Rather than truncate the sweep at the retained comments (which would split the run across the raw prefix and the
     * tracked slots, losing the single contiguous block and the blank line before {@code package}), this method keeps the
     * entire run in the raw prefix and {@linkplain #claimTrackedCommentsInSweep claims the retained comments} so the
     * ownership pre-pass gates those slots out. The run then renders exactly once, from raw text, idempotently. Whether
     * the last line lands as an orphan or as the package's attached leading comment depends on whether a top-level type
     * follows, so both retained shapes are suppressed.
     *
     * <p>The accumulated leading region is stripped of <em>both</em> leading and trailing whitespace before the
     * comment-prefix check. A file/license block ({@code /* ... *}{@code /}) or line ({@code // ...}) comment that does not
     * begin at byte 0 — for example a header preceded by a blank line, as in apache/camel {@code WeaviateVectorDbHeaders}
     * — sweeps that blank-line whitespace token into the leading region, so the region starts with {@code "\n"}. Stripping
     * only the trailing side would leave that leading newline in place, the {@code startsWith("/*")} /
     * {@code startsWith("//")} guard would fail, and the header would be dropped. Stripping both sides recognizes the
     * comment and emits it without the spurious leading blank line; the caller supplies the blank-line separation before
     * {@code package}, so the emitted prefix stays a bare comment block and the result is idempotent across passes.
     */
    Doc sourceLeadingCommentsBeforePackage(CompilationUnit unit) {
        Optional<PackageDeclaration> packageDeclaration = unit.getPackageDeclaration();
        if (packageDeclaration.isEmpty()) {
            return Doc.EMPTY;
        }
        TokenRange tokens = unit.getTokenRange().orElse(null);
        if (tokens == null) {
            return Doc.EMPTY;
        }
        Optional<Comment> attachedLeadingComment = packageDeclaration.flatMap(PackageDeclaration::getComment);
        // A block/javadoc package leading comment is a sweep boundary (#43): it is emitted via the tracked leading slot,
        // so the raw sweep must stop before it. A LINE package leading comment is the trailing line of a multi-line `//`
        // header instead; that whole run belongs to the raw prefix, so the sweep runs through it to the `package` keyword
        // and the line comment is suppressed below rather than re-emitted by the leading slot.
        Optional<Position> sweepBoundary = attachedLeadingComment
                .filter(comment -> !(comment instanceof LineComment))
                .flatMap(PackageDeclarationPrinter::commentBeginPosition);
        Position sweepEnd = null;
        StringBuilder leadingTokens = new StringBuilder();
        boolean foundBoundary = false;
        for (JavaToken token : tokens) {
            if (token.getKind() == GeneratedJavaParserConstants.PACKAGE || reachesSweepBoundary(token, sweepBoundary)) {
                sweepEnd = token.getRange().map(range -> range.begin).orElse(null);
                foundBoundary = true;
                break;
            }
            leadingTokens.append(token.getText());
        }
        if (!foundBoundary) {
            return Doc.EMPTY;
        }
        String leading = leadingTokens.toString().strip();
        if (leading.isEmpty() || (!leading.startsWith("/*") && !leading.startsWith("//"))) {
            return Doc.EMPTY;
        }
        claimTrackedCommentsInSweep(unit, attachedLeadingComment, sweepEnd);
        return Doc.text(
            options.preserveRawTrailingWhitespace()
                ? leading
                : rawSource.stripTrailingHorizontalWhitespace(leading)
        );
    }

    /**
     * Claims the AST-retained comments whose text the raw sweep already emitted verbatim, so no tracked slot renders them
     * a second time.
     *
     * <p>JavaParser does not keep a multi-line {@code //} header before {@code package} as one comment. It drops the first
     * {@code //} line from the AST entirely, and — depending on whether a top-level type follows — retains the later lines
     * either as compilation-unit orphans or, for the last line, as the package declaration's attached leading comment. The
     * raw sweep reconstructs the whole run verbatim from the token stream (the dropped lines are invisible to every
     * tracked slot, so only the raw text carries them), but the retained comments would <em>also</em> be emitted by their
     * tracked slots — the orphans through {@link CompilationUnitPrinter}'s before-first-type orphan slot, the attached
     * line through {@link #packageDeclaration(PackageDeclaration)}'s leading slot — duplicating the last header line(s),
     * splitting the run across two blocks, and gluing a duplicate onto {@code package}. Claiming each retained comment
     * here makes this printer the recorded owner in the ownership dry-run, so {@link CommentTracker#ownsHere} gates those
     * slots out and the run renders once, from the raw text, as one contiguous block with its source blank line before
     * {@code package} intact. The rendered comment docs are intentionally discarded: the claims are for
     * ownership/accounting only, since the verbatim text is already in the returned raw blob.
     *
     * <p>Only comments that begin within the swept region ({@code [start, sweepEnd)}) are claimed, so trailing comments and
     * comments owned by later structure are left to their own nodes. This composes with the block-comment truncation
     * (#43): a {@code /* ... *}{@code /} or {@code /** ... *}{@code /} package leading comment stays the sweep boundary and
     * is left to the leading slot, and earlier detached block header(s) are emitted raw. It also composes with the
     * leading-blank-line strip (#45): a single {@code //} line that JavaParser drops entirely has no retained comment, so
     * this is a no-op and that path is unchanged.
     */
    private void claimTrackedCommentsInSweep(
            CompilationUnit unit,
            Optional<Comment> attachedLeadingComment,
            Position sweepEnd
    ) {
        if (sweepEnd == null) {
            return;
        }
        for (Comment orphan : unit.getOrphanComments()) {
            claimIfWithinSweep(orphan, sweepEnd);
        }
        attachedLeadingComment
                .filter(comment -> comment instanceof LineComment)
                .ifPresent(comment -> claimIfWithinSweep(comment, sweepEnd));
    }

    /**
     * Claims {@code comment} when it begins inside the swept region so the dry-run records this printer as its owner and
     * every later tracked slot is gated out. The comment is anchored to itself under {@link OwnerSlot#INTERLEAVED}, an
     * ownership key no other slot offers, so the suppression cannot collide with a real anchor/slot pair. The rendered doc
     * is discarded because the comment's verbatim text is already in the raw prefix this printer returns.
     */
    private void claimIfWithinSweep(Comment comment, Position sweepEnd) {
        commentBeginPosition(comment)
                .filter(begin -> begin.isBefore(sweepEnd))
                .ifPresent(begin -> comments.comment(comment));
    }

    /**
     * Reports whether {@code token} begins at or after the raw sweep boundary (a block/javadoc package leading comment),
     * which the tracked leading-comment slot will emit separately. Tokens without a source range never reach the boundary,
     * so the sweep falls back to the {@code package} keyword stop exactly as before.
     */
    private static boolean reachesSweepBoundary(JavaToken token, Optional<Position> sweepBoundary) {
        return sweepBoundary
                .flatMap(commentStart -> token.getRange().map(range -> !range.begin.isBefore(commentStart)))
                .orElse(false);
    }

    private static Optional<Position> commentBeginPosition(Comment comment) {
        return comment.getRange().map(range -> range.begin);
    }

    /**
     * Prints one package declaration with its JavaParser-attributed leading comments.
     */
    Doc packageDeclaration(PackageDeclaration declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text("package " + declaration.getNameAsString() + ";"));
    }
}
