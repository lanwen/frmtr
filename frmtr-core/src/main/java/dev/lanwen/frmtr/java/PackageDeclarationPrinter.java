package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;

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

    private final Function<NodeWithAnnotations<?>, Doc> annotations;

    PackageDeclarationPrinter(
            CommentTracker comments,
            RawSource rawSource,
            FormatterOptions options,
            Function<NodeWithAnnotations<?>, Doc> annotations
    ) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
        this.annotations = annotations;
    }

    /**
     * Recovers raw comments that appear in the source before the package declaration token sequence.
     *
     * <p>Only emitted when a package declaration exists and the pre-{@code package} token content starts with a line or
     * block comment; otherwise the caller's normal orphan/leading handling keeps ownership as before. The package
     * boundary is located by the lexer {@code package} keyword token, not a {@code "package <name>"} substring, so
     * non-canonical spacing ({@code package  com . google . common . collect}, as the idempotence/comment-presence
     * perturbations produce) cannot defeat it and silently drop an AST-invisible header.
     *
     * <p>The sweep stops at a block/javadoc comment JavaParser attached to the package as its leading trivia (the
     * {@code package-info.java} license-plus-doc shape, where the last of several comments attaches), which
     * {@link #packageDeclaration(PackageDeclaration)} emits through the tracked
     * {@link CommentTracker#leading(com.github.javaparser.ast.Node)} slot — sweeping it too would duplicate the package
     * doc on every pass. A multi-line {@code //} run is different: JavaParser drops its first line and keeps the rest as
     * orphans (or an attached line comment), so the whole run stays in the raw prefix and the retained comments are
     * {@linkplain #claimTrackedCommentsInSweep claimed} to gate their tracked slots out; the run then renders once,
     * idempotently.
     *
     * <p>The leading region is stripped of both leading and trailing whitespace before the comment-prefix check: a header
     * preceded by a blank line (apache/camel {@code WeaviateVectorDbHeaders}) sweeps that newline in, and stripping only
     * the trailing side would fail the {@code startsWith("/*")}/{@code startsWith("//")} guard and drop the header.
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
        // A block/javadoc package leading comment is a sweep boundary: it is emitted via the tracked leading slot,
        // so the raw sweep must stop before it. A LINE package leading comment is the trailing line of a multi-line `//`
        // header instead; that whole run belongs to the raw prefix, so the sweep runs through it to the `package` keyword
        // and the line comment is suppressed below rather than re-emitted by the leading slot.
        Optional<Position> commentBoundary = attachedLeadingComment
                .filter(comment -> !(comment instanceof LineComment))
                .flatMap(PackageDeclarationPrinter::commentBeginPosition);
        // Package-level annotations are AST nodes rendered structurally by `packageDeclaration`, not raw text. The
        // sweep must stop before the first annotation so its tokens are not dumped verbatim into the leading comment blob
        // (which would also drop them when no preceding comment makes the blob start with `/*` or `//`). The earliest of
        // the comment boundary and the first-annotation begin wins, so a file/package header still renders raw while the
        // annotations are left to the structural annotation path.
        Optional<Position> sweepBoundary = earliest(
            commentBoundary,
            firstAnnotationBegin(packageDeclaration.orElseThrow())
        );
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
     * <p>JavaParser splits a multi-line {@code //} header before {@code package} — dropping the first line and keeping the
     * rest as compilation-unit orphans or an attached leading comment — so the raw sweep is the only path carrying the
     * dropped line. Left alone, the retained comments would <em>also</em> render through their tracked slots
     * ({@link CompilationUnitPrinter}'s before-first-type orphan slot, {@link #packageDeclaration(PackageDeclaration)}'s
     * leading slot), duplicating the run. Claiming each here makes this printer the recorded owner, so
     * {@link CommentTracker#ownsHere} gates those slots out and the run renders once from raw text; the rendered docs are
     * discarded (claims are for ownership only).
     *
     * <p>Only comments within the swept region ({@code [start, sweepEnd)}) are claimed. This composes with the
     * block-comment truncation (a {@code /* ... *}{@code /} package leading comment stays the sweep boundary and its slot)
     * and the blank-line strip (a single dropped {@code //} line has no retained comment, so this is a no-op).
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
     * Returns the source begin position of the first package-level annotation, used as an additional raw-sweep boundary so
     * annotation tokens are rendered structurally rather than dumped into the leading comment blob. Empty when the package
     * carries no annotations or the first annotation has no range, leaving the sweep to fall back to the comment boundary
     * or the {@code package} keyword stop.
     */
    private static Optional<Position> firstAnnotationBegin(PackageDeclaration declaration) {
        return declaration.getAnnotations()
                .stream()
                .map(AnnotationExpr::getRange)
                .flatMap(Optional::stream)
                .map(range -> range.begin)
                .min(Comparator.naturalOrder());
    }

    private static Optional<Position> earliest(Optional<Position> left, Optional<Position> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return Optional.of(left.orElseThrow().isBefore(right.orElseThrow()) ? left.orElseThrow() : right.orElseThrow());
    }

    /**
     * Prints one package declaration with its JavaParser-attributed leading comments and any package-level annotations.
     *
     * <p>Package-level annotations (the {@code package-info.java} shape, e.g. {@code @NullMarked} or {@code @XmlSchema(...)})
     * are AST nodes, so they render structurally through the shared declaration-annotation path — each on its own line
     * above {@code package X;}, wrappable like a type or field annotation. The raw sweep now stops before the first
     * annotation, so this is the only path that emits them (they were previously dropped as an AST-equivalence loss).
     */
    Doc packageDeclaration(PackageDeclaration declaration) {
        return Doc.concat(
            comments.leading(declaration),
            annotations.apply(declaration),
            Doc.text("package " + declaration.getNameAsString() + ";")
        );
    }
}
