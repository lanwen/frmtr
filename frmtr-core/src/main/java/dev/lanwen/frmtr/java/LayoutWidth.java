package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.Optional;

/**
 * Computes formatter line-width probes at the indentation baselines shared by Java layout helpers.
 *
 * <p>The formatter often has to decide whether a flat source-shaped fragment fits before it has a final {@code Doc}.
 * This helper owns the small set of indentation baselines used by those probes so method chains, fields, statements,
 * and declaration helpers do not each re-create their own arithmetic. Callers still decide which candidate text is
 * semantically valid and whether overflow should force a break.
 */
final class LayoutWidth {

    private final FormatterOptions options;

    enum LineBudget {
        /** A top-level member or statement line with one indentation unit already applied. */
        CURRENT(1),

        /** A statement line inside one ordinary Java block. */
        BLOCK(2),

        /** A continuation line under a broken statement or member initializer. */
        CONTINUATION(3),

        /** The closing line of an expression-lambda argument packed inside a broken call argument list. */
        LAMBDA_ARGUMENT_CLOSING(4),

        /** A statement line inside a block-lambda argument that is itself nested under a broken method chain. */
        METHOD_CHAIN_LAMBDA_BODY(5);

        private final int indentLevels;

        LineBudget(int indentLevels) {
            this.indentLevels = indentLevels;
        }

        int width(FormatterOptions options, String text) {
            return (options.indentUnit().length() * indentLevels) + text.length();
        }
    }

    LayoutWidth(FormatterOptions options) {
        this.options = options;
    }

    /**
     * Measures text against an explicitly chosen line budget.
     */
    int line(LineBudget budget, String text) {
        return budget.width(options, text);
    }

    /**
     * Measures text from the current member/statement indentation baseline.
     */
    int currentIndented(String text) {
        return line(LineBudget.CURRENT, text);
    }

    /**
     * Measures a statement candidate inside one ordinary block.
     */
    int blockStatement(String text) {
        return line(LineBudget.BLOCK, text);
    }

    /**
     * Measures a continuation line under a broken statement or member initializer.
     */
    int continuationStatement(String text) {
        return line(LineBudget.CONTINUATION, text);
    }

    /**
     * Measures a line emitted at the node's rendered indentation.
     *
     * <p>Statement fragments that render beside a preceding token, such as {@code } catch (...)}, need the indentation
     * of their source node rather than one of the fixed root budgets. Counting enclosing blocks and types mirrors the
     * indentation that the block and member printers add around the line.
     */
    int nodeLine(Node node, String text) {
        return Math.max(1, renderedIndentLevels(node)) * options.indentUnit().length() + text.stripLeading().length();
    }

    /**
     * Returns the rendered indentation width (in columns) of a line emitted at the node's nesting depth.
     *
     * <p>Callers that already know the visible width of everything after the indentation — for example a hugged
     * {@code outer(inner(} opener plus a reindentation-invariant value prefix measured from source columns — add this
     * baseline themselves instead of folding their fragment into {@link #nodeLine}, where {@code stripLeading} would
     * discard any leading spaces they meant to count.
     */
    int nodeIndentWidth(Node node) {
        return Math.max(1, renderedIndentLevels(node)) * options.indentUnit().length();
    }

    /**
     * Measures a variable initializer at the variable's actual nesting depth.
     *
     * <p>Field and local-variable initializer fallbacks need the enclosing type/block depth, not just the root printer's
     * baseline, because the decision is made before the surrounding declaration has been rendered.
     */
    int variableInitializer(VariableDeclarator variable, String text) {
        return Math.max(1, renderedIndentLevels(variable)) * options.indentUnit().length()
            + text.stripLeading().length();
    }

    private int renderedIndentLevels(Node node) {
        int indentLevels = 0;
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node current = parent.orElseThrow();
            if (current instanceof TypeDeclaration<?> || current instanceof BlockStmt) {
                indentLevels++;
            }
            parent = current.getParentNode();
        }
        return indentLevels;
    }
}
