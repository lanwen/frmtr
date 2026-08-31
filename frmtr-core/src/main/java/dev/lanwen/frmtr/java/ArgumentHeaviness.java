package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a call or constructor argument list is "heavy" enough to break one-argument-per-line even when it
 * technically fits the line.
 *
 * <p>This is a purely STRUCTURAL complexity heuristic over the argument AST — it never reads the author's source shape,
 * so its verdict is a pure function of the parsed arguments and stays stable across formatter passes (idempotent by
 * construction). It owns only the boolean predicate; the method-call, object-creation, and explicit-constructor printers
 * still own how they turn a {@code true} into a broken document (they compose it with their existing width-driven
 * {@link dev.lanwen.frmtr.doc.Doc#group} by injecting a {@link dev.lanwen.frmtr.doc.Doc#BREAK_PARENT}, so a heavy list
 * breaks exactly the way an over-wide one already does — including the outward cascade a forced break triggers in every
 * enclosing group).
 *
 * <p>Two independent signals mark a list heavy:
 * <ul>
 *   <li><b>Wide constructor lists</b> — a constructor with {@value #LARGE_ARGUMENT_COUNT} or more arguments is hard to
 *       read on one line regardless of what the arguments are. This applies only when the caller opts in
 *       ({@code applyLargeArgumentCountRule}), which the object-creation and {@code super(...)}/{@code this(...)} printers
 *       do and the plain method-call printer does not: a five-argument <em>method</em> call is common enough that
 *       forcing every one to explode is disproportionate, so method calls rely on the nested-token signal below.</li>
 *   <li><b>Deeply/nestedly composed lists</b> — an argument list that both contains a nested call/constructor argument
 *       (an "argument that itself has arguments") and reaches a token count of {@value #HEAVY_TOKEN_THRESHOLD} is too
 *       dense to scan on one line. This counts tokens, not pure arguments, and fires for both method
 *       calls and constructors.</li>
 * </ul>
 *
 * <p>The nesting cascade — a broken nested constructor forcing the enclosing call to break too — is not
 * implemented here as an explicit parent-propagation rule: a nested heavy call already emits a forced break, and a forced
 * break poisons the flat measurement of every enclosing {@code Doc.group} (see {@code DocWidths}), so the enclosing
 * argument lists break automatically. The token count additionally rolls a nested call's arguments into its parent's
 * score, so a parent that holds a dense nested call usually crosses the threshold on its own as well.
 */
final class ArgumentHeaviness {

    /**
     * A constructor argument list of this size or larger always breaks. Applied only for the opt-in constructor callers.
     */
    static final int LARGE_ARGUMENT_COUNT = 5;

    /**
     * The token count at or above which an argument list that contains nested call/constructor arguments breaks. The
     * gate is density, not nesting depth: a list dense enough to reach this count is hard to scan on one line, while a
     * single nested call in an otherwise short list stays compact as long as it fits.
     */
    static final int HEAVY_TOKEN_THRESHOLD = 12;

    /**
     * Reports whether {@code arguments} should break one-argument-per-line even when it fits the line width.
     *
     * @param arguments the direct argument list being laid out
     * @param applyLargeArgumentCountRule whether the wide-list rule ({@link #LARGE_ARGUMENT_COUNT}) applies — constructor
     *     callers pass {@code true}, plain method calls pass {@code false}
     */
    boolean isHeavy(NodeList<Expression> arguments, boolean applyLargeArgumentCountRule) {
        if (arguments.isEmpty()) {
            return false;
        }
        if (applyLargeArgumentCountRule && arguments.size() >= LARGE_ARGUMENT_COUNT) {
            return true;
        }
        return hasNestedCallArgument(arguments) && tokenCount(arguments) >= HEAVY_TOKEN_THRESHOLD;
    }

    /**
     * Reports whether {@code node}'s subtree contains a call or constructor whose own argument list is heavy — i.e. a
     * list that {@link #isHeavy} would force to break. Callers use this to refuse a compact/flat rendering of an argument
     * that would otherwise hide a mandated break (for example a chain argument {@code new Wide(a, b, c, d, e).build()}
     * whose constructor root must still break its arguments even when the whole chain fits on one line).
     *
     * <p>The scan applies the wide-argument-count rule to constructors ({@code true}) and only the nested-token rule to
     * method calls ({@code false}), matching how the printers opt in, and skips lambda/anonymous-class bodies for the same
     * reason the token count does.
     */
    boolean containsHeavyArgumentList(Node node) {
        if (node instanceof LambdaExpr) {
            return false;
        }
        if (node instanceof MethodCallExpr call && isHeavy(call.getArguments(), false)) {
            return true;
        }
        if (node instanceof ObjectCreationExpr creation && isHeavy(creation.getArguments(), true)) {
            return true;
        }
        for (Node child : structuralChildren(node)) {
            if (containsHeavyArgumentList(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the argument-list "tokens": every argument slot plus, for each nested call/constructor reached in the
     * argument subtree, itself ({@code +1}) and its own argument slots. A leaf argument (name, literal, field access)
     * contributes only its slot; a nested call contributes its slot, one for the call, and its own arguments recursively.
     * Lambda and anonymous-class bodies are treated as opaque — their inner statements are a separate block that hugs or
     * breaks on its own and does not make the enclosing argument list denser to read.
     */
    private int tokenCount(NodeList<Expression> arguments) {
        int count = arguments.size();
        for (Expression argument : arguments) {
            count += nestedWeight(argument);
        }
        return count;
    }

    private int nestedWeight(Node node) {
        if (node instanceof LambdaExpr) {
            return 0;
        }
        int weight = switch (node) {
            case MethodCallExpr call -> 1 + call.getArguments().size();
            case ObjectCreationExpr creation -> 1 + creation.getArguments().size();
            default -> 0;
        };
        for (Node child : structuralChildren(node)) {
            weight += nestedWeight(child);
        }
        return weight;
    }

    private boolean hasNestedCallArgument(NodeList<Expression> arguments) {
        for (Expression argument : arguments) {
            if (containsCallWithArguments(argument)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCallWithArguments(Node node) {
        if (node instanceof LambdaExpr) {
            return false;
        }
        if (node instanceof MethodCallExpr call && !call.getArguments().isEmpty()) {
            return true;
        }
        if (node instanceof ObjectCreationExpr creation && !creation.getArguments().isEmpty()) {
            return true;
        }
        for (Node child : structuralChildren(node)) {
            if (containsCallWithArguments(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The child nodes that count toward argument heaviness. For an anonymous-class constructor this is the scope and the
     * constructor arguments but NOT the class body: the body owns its own multi-line layout, so its members must not
     * inflate the header's argument-list density. Every other node exposes its natural children.
     */
    private List<Node> structuralChildren(Node node) {
        if (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
            List<Node> children = new ArrayList<>();
            creation.getScope().ifPresent(children::add);
            children.addAll(creation.getArguments());
            return children;
        }
        return node.getChildNodes();
    }
}
