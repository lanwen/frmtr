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

    LayoutWidth(FormatterOptions options) {
        this.options = options;
    }

    /**
     * Measures text from the current member/statement indentation baseline.
     */
    int currentIndented(String text) {
        return options.indentUnit().length() + text.length();
    }

    /**
     * Measures a statement candidate inside one ordinary block.
     */
    int blockStatement(String text) {
        return (options.indentUnit().length() * 2) + text.length();
    }

    /**
     * Measures a continuation line under a broken statement or member initializer.
     */
    int continuationStatement(String text) {
        return (options.indentUnit().length() * 3) + text.length();
    }

    /**
     * Measures a variable initializer at the variable's actual nesting depth.
     *
     * <p>Field and local-variable initializer fallbacks need the enclosing type/block depth, not just the root printer's
     * baseline, because the decision is made before the surrounding declaration has been rendered.
     */
    int variableInitializer(VariableDeclarator variable, String text) {
        int indentLevels = 0;
        Optional<Node> parent = variable.getParentNode();
        while (parent.isPresent()) {
            Node node = parent.orElseThrow();
            if (node instanceof TypeDeclaration<?> || node instanceof BlockStmt) {
                indentLevels++;
            }
            parent = node.getParentNode();
        }
        return Math.max(1, indentLevels) * options.indentUnit().length() + text.stripLeading().length();
    }
}
