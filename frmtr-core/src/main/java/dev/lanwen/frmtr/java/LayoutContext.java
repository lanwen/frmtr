package dev.lanwen.frmtr.java;

/**
 * Carries the per-node <em>positional</em> facts a layout rule needs about <em>where</em> the node it is formatting
 * sits — distinct from run-scoped services and from per-type dispatch.
 *
 * <p>frmtr separates three concerns. Run-scoped services (options, comment tracking, source-shape policy, width
 * arithmetic, …) live on {@link JavaFormatContext}, whose own Javadoc forbids it from becoming a per-node service
 * locator. Per-node-<em>type</em> formatting (render an {@code AssignExpr} versus a {@code LambdaExpr}) lives on
 * {@link JavaFormatRule} and the dispatchers ({@link ExpressionDispatcher}, {@link BodyDeclarationDispatcher},
 * {@link EnclosedSuffixDispatcher}). The third concern — the per-node positional context (am I a return value? an
 * initializer right-hand side? what prefix shares my first line?) — is this value, threaded down the descent rather
 * than re-derived from the AST or leaked into printer constructor parameters.
 *
 * <p>It is an immutable record, passed as a parameter and <em>never</em> stored as a field and <em>never</em> mutated.
 * A descent that wants a different position produces a fresh value; trying a candidate layout is calling the rule with
 * a derived context and discarding the result, so there is no shared mutation to roll back. Being a plain record with
 * no reflection keeps it native-image safe.
 *
 * @param enclosing the syntactic position this node occupies relative to its parent
 * @param leftEdgePrefix text that already occupies this node's first line ahead of it (for example an assignment
 *     prefix); the empty string when the node owns its own first column
 * @param widthBudget <strong>transitional.</strong> The {@link LayoutWidth.LineBudget} a width probe should assume for
 *     this node. The end state measures fit at the node's real rendered column via the renderer, so this selector is a
 *     crutch and is removed when measurement parity (LDM-2 / C10) lands; until then it reproduces today's fixed
 *     baselines so no probe value changes
 * @param trailingContent same-line content the <em>caller</em> will emit immediately after this node, which node-local
 *     IR cannot see but a width gate must account for. The canonical case is a declaration header's throws clause: its
 *     width has to include the {@code " {"} (body) or {@code ";"} (abstract) opener the caller appends on the same
 *     line, so the gate is fed that opener here rather than guessing it node-locally. The empty string when no
 *     same-line content follows (the common case, e.g. a statement or a return value that ends its own line). It is a
 *     positional fact — <em>where</em> this node sits relative to what the caller emits after it — and so lives on the
 *     context, distinct from the node's own rendered text
 * @param leadingBreak whether the caller has already committed this node to lead with a break, so an enclosed
 *     call/reference receiver ({@code (…).method(…)}, {@code (…)::member}) must break its parenthesized scope
 *     unconditionally instead of first gating on its own width. The canonical case is an assignment or variable
 *     initializer right-hand side whose surrounding line has already been decided too wide to keep flat: the
 *     suffix-preserving dispatcher is told "you are already past the break decision" here rather than re-deriving it.
 *     {@code false} when no such decision has been made and the receiver is free to stay compact if it fits (the
 *     common case). It is a positional fact — <em>whether this node's position is already inside a broken line</em> —
 *     and so lives on the context, distinct from the node's own rendered text, which is why {@link
 *     EnclosedSuffixDispatcher} reads it from here (#189) rather than carrying it as a separate dispatch argument
 */
record LayoutContext(
    EnclosingConstruct enclosing,
    String leftEdgePrefix,
    LayoutWidth.LineBudget widthBudget,
    String trailingContent,
    boolean leadingBreak
) {

    /**
     * The compilation-unit-level starting context: at the root, with no left-edge prefix, no caller-emitted trailing
     * content, no committed leading break, and the current-line width budget. This reproduces the defaults every call
     * site assumed before {@code LayoutContext} existed, so threading it through changes no formatting decision.
     */
    static LayoutContext root() {
        return new LayoutContext(EnclosingConstruct.ROOT, "", LayoutWidth.LineBudget.CURRENT, "", false);
    }

    /**
     * Derives a copy of this context whose caller will emit {@code trailingContent} on the same line immediately after
     * the node — for example a declaration header handing its throws-clause gate the {@code " {"} body opener it is
     * about to append. Every other positional fact is preserved. Following the {@code LayoutContext} discipline this
     * produces a fresh value rather than mutating; the original is unchanged.
     */
    LayoutContext withTrailingContent(String trailingContent) {
        return new LayoutContext(enclosing, leftEdgePrefix, widthBudget, trailingContent, leadingBreak);
    }

    /**
     * Derives a copy of this context that records whether the node is already committed to lead with a break — for
     * example an assignment or initializer right-hand side whose surrounding line the caller has already decided must
     * break, so an enclosed suffix receiver breaks its parenthesized scope unconditionally instead of re-gating on its
     * own width. Every other positional fact is preserved. Following the {@code LayoutContext} discipline this produces
     * a fresh value rather than mutating; the original is unchanged.
     */
    LayoutContext withLeadingBreak(boolean leadingBreak) {
        return new LayoutContext(enclosing, leftEdgePrefix, widthBudget, trailingContent, leadingBreak);
    }
}
