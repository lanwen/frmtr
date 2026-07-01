package dev.lanwen.frmtr.java;

/**
 * Names the syntactic position a node occupies relative to its parent so layout rules can branch on <em>where</em> a
 * node sits without re-deriving that fact from the AST on each descent.
 *
 * <p>This enum is the positional half of {@link LayoutContext}: it answers "am I a statement, an initializer
 * right-hand side, a call argument, a returned value, a condition?" The dispatcher that owns per-node-<em>type</em>
 * formatting ({@link ExpressionDispatcher} and friends) stays orthogonal to this; an {@code AssignExpr} is rendered by
 * the same rule whether it is a statement or an argument, but the rule may eventually consult this position to choose a
 * broken shape.
 *
 * <p>Only arms that a caller can thread sensibly today are listed. New positions are added when a descent path can
 * supply them and a rule consumes them — the codebase forbids carrying values nothing reads, so this stays minimal and
 * grows with the consuming rules in later layout-decision-model milestones.
 */
enum EnclosingConstruct {

    /** The compilation-unit-level entry point: no enclosing layout context has been established yet. */
    ROOT,

    /** A statement occupying its own line inside a block. */
    STATEMENT,

    /** The right-hand side of an assignment or variable initializer. */
    INITIALIZER_RHS,

    /** An expression supplied as a call or constructor argument. */
    ARGUMENT,

    /** The value of a {@code return} statement. */
    RETURN_VALUE,

    /** The controlling expression of an {@code if}, loop, or other condition. */
    CONDITION
}
