package dev.lanwen.frmtr;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse-preserving whitespace perturbation of Java source, shared by the two corpus tests that re-shape every golden
 * fixture input to broaden coverage beyond the hand-authored layouts.
 *
 * <p>This helper owns one concern: rewriting <em>only</em> the whitespace between tokens so the lexed token sequence is
 * identical except for the amount of whitespace. Because every identifier, keyword, literal (string and text-block
 * included), separator, operator, and comment token is emitted verbatim, the perturbed source parses to the same AST and
 * no literal or comment content is altered. The boundary exists because {@code IdempotencePropertyTest} (semantic
 * preservation / convergence) and {@code CommentPresenceDiagnosticTest} (comment-presence net) must perturb the corpus
 * <em>identically</em> — their cases line up 1:1 — so a single implementation is the only way to keep that guarantee
 * instead of two copies drifting apart.
 *
 * <p>Callers still decide what to assert about the perturbed source (idempotence, convergence, comment presence), which
 * options to format under, and which perturbed cases — if any — to skip as findings.
 */
public final class SourceShapePerturbation {

    private SourceShapePerturbation() {}

    /** The two parse-preserving whitespace re-shapes applied to a fixture input. */
    public enum Shape {
        /** Collapse each whitespace run to the minimum that keeps the token stream valid. */
        COLLAPSE,
        /** Expand each whitespace run with extra spacing and blank lines. */
        EXPAND,
    }

    /**
     * Re-shapes {@code source} by rewriting only its whitespace tokens, returning {@code null} when the source does not
     * parse (so it can be skipped).
     *
     * <p>The rewrite walks JavaParser's own token stream and emits every non-whitespace token — identifiers, keywords,
     * <strong>literals</strong> (including string and text-block literals), separators, operators, and
     * <strong>comments</strong> — verbatim, perturbing only the whitespace between them. That is what makes the
     * perturbation parse-preserving and value-preserving: the lexed token sequence is identical except for the amount of
     * whitespace, so the perturbed source parses to the same AST and no literal or comment content is altered. A single
     * end-of-line is preserved immediately after a line comment, because a {@code //} comment without a following
     * newline would swallow the next token and change the program.
     *
     * <p><strong>Line-significant pragma comments keep their own line.</strong> A formatter pragma / ignore marker
     * (e.g. {@code // @formatter:off}, {@code // @formatter:on}, {@code // frmtr-ignore[-start|-end]}) is line-based: the
     * formatter attaches it to whatever syntax node it shares a line with, so the region it protects is defined by its
     * line position. Collapsing the whitespace <em>before</em> such a comment would slide it onto the previous statement's
     * line, changing which region the user asked to protect — a meaning-changing perturbation, not a whitespace-only one.
     * The same way string interiors are emitted verbatim, the perturbation therefore preserves a newline immediately
     * before (and after) a line-significant pragma comment, keeping it on its own line. This is the principled analogue of
     * leaving literal interiors untouched: the comment's line is part of its meaning here.
     */
    public static String perturb(String source, Shape shape) {
        TokenRange tokens = parseTokenRange(source);
        if (tokens == null) {
            return null;
        }
        List<JavaToken> tokenList = new ArrayList<>();
        for (JavaToken token : tokens) {
            tokenList.add(token);
        }
        StringBuilder builder = new StringBuilder(source.length() * 2);
        JavaToken previous = null;
        for (int index = 0; index < tokenList.size(); index++) {
            JavaToken token = tokenList.get(index);
            if (token.getCategory().isWhitespace()) {
                builder.append(whitespaceFor(shape, previous, nextNonWhitespace(tokenList, index)));
            } else {
                builder.append(token.getText());
                previous = token;
            }
        }
        return builder.toString();
    }

    private static TokenRange parseTokenRange(String source) {
        return newParser()
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .flatMap(CompilationUnit::getTokenRange)
                .orElse(null);
    }

    private static JavaParser newParser() {
        // BLEEDING_EDGE matches the language level the default-options formatter uses, so the token walk lexes the same
        // way the formatter will; tokens and comments are stored so whitespace and comment categories are visible.
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(true)
                .setAttributeComments(true));
    }

    private static JavaToken nextNonWhitespace(List<JavaToken> tokens, int from) {
        for (int index = from + 1; index < tokens.size(); index++) {
            JavaToken token = tokens.get(index);
            if (!token.getCategory().isWhitespace()) {
                return token;
            }
        }
        return null;
    }

    private static String whitespaceFor(Shape shape, JavaToken previous, JavaToken next) {
        boolean afterLineComment = previous != null
                && previous.getCategory().isComment()
                && previous.getText().startsWith("//");
        boolean beforePragmaComment = next != null && next.getCategory().isComment() && isLinePragma(next.getText());
        if (afterLineComment || beforePragmaComment) {
            // A line comment must be terminated by a newline or it consumes whatever follows; a line-significant pragma
            // must keep its own line or it would change which region the user asked to protect.
            return shape == Shape.EXPAND ? "\n\n" : "\n";
        }
        return shape == Shape.EXPAND ? "  \n  " : " ";
    }

    /**
     * Reports whether a comment's text carries a line-significant formatter pragma / ignore marker. Mirrors the marker
     * vocabulary {@code FormatterPragmas} recognizes; the perturbation keeps such a comment on its own line so collapsing
     * whitespace cannot move it into a different protected region.
     */
    private static boolean isLinePragma(String commentText) {
        return commentText.contains("@formatter:off")
                || commentText.contains("@formatter:on")
                || commentText.contains("frmtr-ignore");
    }
}
