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
 */
record LayoutContext(
    EnclosingConstruct enclosing,
    String leftEdgePrefix,
    LayoutWidth.LineBudget widthBudget
) {

    /**
     * The compilation-unit-level starting context: at the root, with no left-edge prefix and the current-line width
     * budget. This reproduces the defaults every call site assumed before {@code LayoutContext} existed, so threading
     * it through changes no formatting decision.
     */
    static LayoutContext root() {
        return new LayoutContext(EnclosingConstruct.ROOT, "", LayoutWidth.LineBudget.CURRENT);
    }
}
