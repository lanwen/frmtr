package dev.lanwen.frmtr;

public final class Frmtr {

    private Frmtr() {}

    public static String format(String source) {
        return format(source, FormatterOptions.defaults());
    }

    public static String format(String source, FormatterOptions options) {
        return session(options).format(source);
    }

    /**
     * Formats the source with formatter defaults and verifies the result is AST-equivalent to the input.
     *
     * <p>Convenience overload of {@link #formatVerified(String, FormatterOptions)} using
     * {@link FormatterOptions#defaults()}.
     */
    public static String formatVerified(String source) {
        return formatVerified(source, FormatterOptions.defaults());
    }

    /**
     * Formats the source and, for cleanly-parsed input, verifies the formatted output is AST-equivalent to the input.
     *
     * <p>This is the opt-in safety valve behind the CLI {@code --write --verify} mode. Unlike
     * {@link #format(String, FormatterOptions)}, it always re-parses the formatted output and compares it structurally
     * to the input (independent of the {@code dev.lanwen.frmtr.debug.verify} debug toggle), accepting the doubled parse
     * cost in exchange for the guarantee. On a mismatch it throws a non-internal {@link FormatterException} so the
     * failure reads as a deliberate refusal rather than an internal bug. Verification is skipped for recovered
     * (partially-parsed) inputs, where AST-equivalence is ill-defined.
     */
    public static String formatVerified(String source, FormatterOptions options) {
        return session(options).formatVerified(source);
    }

    public static String debugDoc(String source) {
        return debugDoc(source, FormatterOptions.defaults());
    }

    /**
     * Formats the source with formatter defaults and reports each output line's structural indentation.
     *
     * <p>Convenience overload of {@link #formatIndented(String, FormatterOptions)} using {@link FormatterOptions#defaults()}.
     */
    public static IndentedSource formatIndented(String source) {
        return formatIndented(source, FormatterOptions.defaults());
    }

    /**
     * Formats the source and reports, for each output line, whether its leading indentation is a structural indent the
     * formatter chose and at which indent level.
     *
     * <p>{@link IndentedSource#text()} is byte-for-byte identical to {@link #format(String, FormatterOptions)} for the
     * same input and options, so requesting the indentation signal never changes formatting. The signal exists so a
     * visualization (the CLI {@code --render-indentation}) can tell a block indent from a continuation indent — a
     * distinction the finished text cannot carry, since both are just leading whitespace and tabs make column counting
     * ambiguous, whereas the renderer knows the true indent level at every newline.
     */
    public static IndentedSource formatIndented(String source, FormatterOptions options) {
        return session(options).formatIndented(source);
    }

    public static ExplainResult explain(String source) {
        return explain(source, FormatterOptions.defaults());
    }

    /**
     * Formats the source and explains the renderer's per-group break/flat decisions in one pass.
     *
     * <p>This is the developer-facing diagnostic entry point behind the CLI {@code --explain} mode. The returned
     * {@link ExplainResult#formatted()} is identical to {@link #format(String, FormatterOptions)} for the same input, so
     * explaining never changes formatting policy or output; the explanation only observes the same render. Like {@link
     * #debugDoc(String, FormatterOptions)}, this always builds the document even under a require-pragma gate.
     */
    public static ExplainResult explain(String source, FormatterOptions options) {
        return session(options).explain(source);
    }

    /**
     * Returns the structural document tree produced by the Java formatter before width-based rendering.
     *
     * <p>This debug path always builds the document tree for the supplied source; pragma gating only controls formatted
     * source output.
     */
    public static String debugDoc(String source, FormatterOptions options) {
        return session(options).debugDoc(source);
    }

    /**
     * Creates a reusable sequential formatter session with the supplied formatter policy.
     *
     * <p>Use one session at a time from one thread only. The session owns a reusable JavaParser instance, which is not
     * thread-safe.
     */
    public static FrmtrSession session(FormatterOptions options) {
        return FrmtrSession.create(options);
    }
}
