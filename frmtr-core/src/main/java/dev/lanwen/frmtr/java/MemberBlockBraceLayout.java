package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Locates a member block's brace interior and the line comment that trails its opening brace.
 *
 * <p>This helper owns the raw-source geometry around a type body's {@code { ... }} braces: it scans the owner's token
 * range to find the interior region between the matching braces, and it detects the {@code //} comment written after the
 * opening brace on the brace line rather than as a declaration's own trivia. The boundary exists so
 * {@link MemberBlockPrinter} can keep member sequencing, separator, and blank-line decisions free of token-range and
 * comment-range scanning. It renders only the opening-brace trailing comment; it does not decide member ordering,
 * separators, or how any declaration inside the block is formatted, and it leaves parse-error recovery framing to the
 * caller (which reuses the interior region it exposes).
 */
final class MemberBlockBraceLayout {

    private final CommentTracker comments;

    private final SourceText sourceText;

    private final JavaCommentPlacementPolicy commentPlacement;

    MemberBlockBraceLayout(
            CommentTracker comments,
            SourceText sourceText,
            JavaCommentPlacementPolicy commentPlacement
    ) {
        this.comments = comments;
        this.sourceText = sourceText;
        this.commentPlacement = commentPlacement;
    }

    /**
     * Recovers a line comment written after the opening brace of a member block.
     *
     * <p>This raw-source path handles the layout case where the comment belongs to the brace line rather than to a
     * declaration. Comments after the first newline continue through normal orphan-comment sequencing.
     */
    Doc openingBraceTrailingLineComment(Node node) {
        return openingBraceTrailingLineCommentTrivia(node)
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    Optional<SourceRegion> openingBraceTrailingLineCommentRegion(Node owner) {
        return openingBraceTrailingLineCommentTrivia(owner)
                .flatMap(comment -> comment.comment().getRange())
                .map(sourceText::region);
    }

    private Optional<JavaCommentTrivia> openingBraceTrailingLineCommentTrivia(Node owner) {
        SourceRegion interior;
        try {
            interior = memberBlockInteriorRegion(owner);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        String raw = sourceText.slice(interior);
        int lineEnd = firstLineEnd(raw);
        int lineEndOffset = lineEnd < 0 ? interior.endOffset() : interior.beginOffset() + lineEnd;
        // A comment only trails the opening brace when nothing else in the block precedes it. When whitespace is
        // collapsed the opening brace and the first member can share a physical line, so the first line break is no
        // longer a reliable boundary: a member's own trailing comment (e.g. an enum constant's `// note`) would sit on
        // the same line as the brace and be mistaken for a brace comment, stealing it from the member that owns it.
        // Cap the boundary at the first member's source start so only comments written before any member qualify.
        int firstLineEndOffset = Math.min(lineEndOffset, firstMemberBeginOffset(owner, interior).orElse(lineEndOffset));
        return commentPlacement.containedComments(owner)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.comment()
                            .getRange()
                            .map(sourceText::region)
                            .filter(region -> RecoveredRawGapPrinter.contains(interior, region))
                            .filter(region -> region.beginLine() == interior.beginLine())
                            .filter(region -> region.beginOffset() < firstLineEndOffset)
                            .isPresent()
                )
                .findFirst();
    }

    /**
     * Finds the source begin offset of the first content child written inside {@code owner}'s brace-delimited body.
     *
     * <p>Used to bound the opening-brace trailing-comment scan: a comment can only trail the opening brace if it is
     * written before any member or enum constant. Only child nodes that begin inside {@code interior} (after the opening
     * brace) count — the owner's own name, type parameters, and {@code extends}/{@code implements} clauses sit before the
     * brace and must not pull the boundary back ahead of a legitimate brace-line comment. Comments are skipped because
     * they are exactly what the scan is trying to classify; only non-comment members mark where body content starts.
     */
    private Optional<Integer> firstMemberBeginOffset(Node owner, SourceRegion interior) {
        return owner.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof com.github.javaparser.ast.comments.Comment))
                .flatMap(child -> child.getRange().stream())
                .map(range -> sourceText.region(range).beginOffset())
                .filter(offset -> offset >= interior.beginOffset())
                .min(Integer::compareTo);
    }

    SourceRegion memberBlockInteriorRegion(Node owner) {
        List<JavaToken> tokens = owner.getTokenRange()
                .map(tokenRange -> {
                    List<JavaToken> collected = new ArrayList<>();
                    tokenRange.forEach(collected::add);
                    return collected;
                })
                .orElseThrow(() -> new IllegalArgumentException("member block owner is missing a token range"));
        JavaToken closingBrace = null;
        JavaToken openingBrace = null;
        int depth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            JavaToken token = tokens.get(i);
            if (token.getKind() == GeneratedJavaParserConstants.RBRACE) {
                if (closingBrace == null) {
                    closingBrace = token;
                }
                depth++;
                continue;
            }
            if (token.getKind() == GeneratedJavaParserConstants.LBRACE && closingBrace != null) {
                depth--;
                if (depth == 0) {
                    openingBrace = token;
                    break;
                }
            }
        }
        if (openingBrace == null || closingBrace == null) {
            throw new IllegalArgumentException("member block source range must contain matching braces");
        }
        SourceRegion openingRegion = tokenRegion(openingBrace, "opening brace");
        SourceRegion closingRegion = tokenRegion(closingBrace, "closing brace");
        if (closingRegion.beginOffset() < openingRegion.endOffset()) {
            throw new IllegalArgumentException("member block braces are not ordered");
        }
        return sourceText.region(openingRegion.endOffset(), closingRegion.beginOffset());
    }

    private SourceRegion tokenRegion(JavaToken token, String description) {
        return token.getRange()
                .map(sourceText::region)
                .orElseThrow(
                    () -> new IllegalArgumentException("member block " + description + " is missing a source range")
                );
    }

    private static int firstLineEnd(String raw) {
        int lf = raw.indexOf('\n');
        int cr = raw.indexOf('\r');
        if (lf < 0) {
            return cr;
        }
        if (cr < 0) {
            return lf;
        }
        return Math.min(lf, cr);
    }
}
