package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Renders Java text-block literal expressions after broad expression dispatch has selected text-block syntax.
 *
 * <p>This helper owns one concern: emitting a text block whose JLS-computed {@code String} value is byte-for-byte the
 * value of the source literal. A formatter must never change a string literal's value, and a text block is a string
 * literal, so its content (escapes, significant whitespace, blank lines) is program data, not layout. The boundary
 * exists because text-block literals need source-token spelling, while the rest of expression dispatch only needs a
 * rendered doc once it knows the expression is a text block.
 *
 * <p>This helper intentionally does <em>not</em> attempt to recognize or reformat embedded languages (HTML, JSON, Java,
 * TypeScript, …). Reformatting embedded content would resolve escapes, collapse interior whitespace, or reflow lines,
 * all of which change the runtime value; the formatter leaves that decision to no one — the value is preserved as
 * written.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch and the surrounding statement/declaration pipeline.
 * {@link MethodCallPrinter} still decides when a single text-block argument should be isolated from the call prefix; it
 * asks this helper only for the literal text that preserves the source text-block layout.
 */
final class TextBlockPrinter {

    private final RawSource rawSource;

    TextBlockPrinter(RawSource rawSource) {
        this.rawSource = rawSource;
    }

    /**
     * Renders a text-block expression while preserving its JLS-computed {@code String} value exactly.
     *
     * <p>The body is reproduced from the original source token, so escapes, significant whitespace, blank lines, and the
     * closing-delimiter placement stay exactly as written. No embedded-language reformatting is applied, because that
     * could change the runtime value of the literal.
     */
    Doc textBlockLiteral(TextBlockLiteralExpr expression) {
        return Doc.text(renderUnformattedTextBlock(expression));
    }

    /**
     * Renders a text block from its original source token, preserving the literal value exactly.
     *
     * <p>Text blocks may carry layout-sensitive config, SQL, shell, traces, JSON, HTML, or other snippets whose escapes
     * and incidental/significant indentation are part of the value the program will observe. The formatter must not
     * reinterpret them, so this path preserves the literal body and closing delimiter exactly as written.
     */
    String renderUnformattedTextBlock(TextBlockLiteralExpr expression) {
        return rawSource.raw(expression);
    }
}
