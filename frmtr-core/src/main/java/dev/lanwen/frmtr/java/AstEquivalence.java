package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.visitor.EqualsVisitor;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration.ConfigOption;
import com.github.javaparser.printer.configuration.PrinterConfiguration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decides whether two {@link CompilationUnit}s represent the same Java program modulo formatting trivia.
 *
 * <p>This helper owns the formatter's notion of "semantic equivalence" for the AST-equivalence verify mode (roadmap B3,
 * layer 1). It exists so the runtime verify hook in {@link JavaFormatter} and the unit tests can share one sound,
 * well-justified comparison instead of each re-deriving which differences are trivia. The verifier re-parses the
 * formatter's <em>output</em> and asks this helper whether it still means the same thing as the <em>input</em>.
 *
 * <p><strong>What counts as trivia (ignored):</strong>
 *
 * <ul>
 *   <li><em>Whitespace, indentation, line breaks, and source positions</em> — reformatting changes all of these by
 *       design; none affect program meaning.
 *   <li><em>Comments (text and placement)</em> — comments are not program meaning. Comment <em>loss</em> is a separate
 *       concern already covered by {@link FormatterGuardrails} comment accounting and by the golden fixtures, so it is
 *       intentionally out of scope here. We strip comments from clones <em>and</em> disable comment printing so a
 *       moved or reflowed comment never reads as a semantic change.
 *   <li><em>Text-block content</em> — the body of a {@link TextBlockLiteralExpr} is its JLS String value, i.e. program
 *       data, so a change to it <em>is</em> a meaning change and is in scope. Each text block is replaced on both trees
 *       by a {@link StringLiteralExpr} of its JLS-computed value ({@link TextBlockLiteralExpr#translateEscapes()}:
 *       incidental indentation removed, escapes translated), so re-indentation — the only text-block change the
 *       value-preserving {@code TextBlockPrinter} makes — does not read as a difference, while any real content change
 *       does. Replacing the literal (rather than dropping it) also keeps a text block deleted or replaced by a
 *       non-text-block expression structurally divergent.
 *   <li><em>Import declaration order</em> — {@link ImportSortTransform} deliberately reorders imports. Java import
 *       order carries no meaning, so this helper canonicalizes both trees' import lists with the formatter's own
 *       {@link ImportSortTransform#FORMATTER_IMPORT_ORDER} before comparing. A <em>dropped</em> or <em>duplicated</em>
 *       import survives that canonicalization (the lists then differ in length or content) and is still reported as a
 *       genuine difference — imports are never simply ignored.
 *   <li><em>Presentation-only syntax the formatter legitimately rewrites.</em> The formatter performs a handful of
 *       semantics-preserving syntactic rewrites that change the AST shape without changing meaning; comparing the raw
 *       re-print would flag these as false positives, so both trees are canonicalized through the same normalization
 *       pass first ({@link #canonicalizePresentation}):
 *       <ul>
 *         <li><em>Parentheses</em> ({@link EnclosedExpr}) are unwrapped on both sides. The formatter both adds
 *             clarifying parentheses to mixed-precedence expressions ({@code a && b || c} to {@code (a && b) || c}) and
 *             removes redundant ones, so the wrapper count is not stable across formatting. This is sound only because
 *             the final comparison is <em>structural</em> (see below), not a printed-string compare: operator
 *             precedence is encoded in the tree shape, so {@code (1 + 2) * 3} (root {@code *}) and {@code 1 + 2 * 3}
 *             (root {@code +}) remain different trees after both lose their {@link EnclosedExpr} wrappers, while
 *             {@code a && b || c} and {@code (a && b) || c} collapse to the same tree.
 *         <li><em>Lambda parameter parenthesization</em> ({@link LambdaExpr#isEnclosingParameters()}) is forced to one
 *             canonical value. Whether a single-parameter lambda writes {@code x ->} or {@code (x) ->} is pure syntax,
 *             and the formatter's {@code LambdaArrowParens} option deliberately rewrites it.
 *         <li><em>Modifier order</em> is sorted into a canonical order. The formatter reorders modifiers (e.g.
 *             {@code static sealed abstract} to {@code abstract static sealed}); Java attaches no meaning to modifier
 *             order.
 *         <li><em>Redundant empty statements</em> ({@code ;}) inside a {@link BlockStmt} are dropped. A stray
 *             semicolon in a block is a no-op the formatter cleans up; it carries no meaning. Empty statements that are
 *             a loop body (e.g. {@code while (c);}) are <em>not</em> in a block list and are intentionally preserved,
 *             because removing them there would change control flow.
 *       </ul>
 * </ul>
 *
 * <p><strong>What it is sensitive to:</strong> identifiers, literals (compared by their exact lexeme, so {@code 0x1p-1}
 * is not silently equated with {@code 0.5}), operators, the tree shape that encodes operator precedence, modifiers,
 * type arguments, statement order, and — the bug class that motivated this layer — the presence and count of enum
 * constants.
 *
 * <p><strong>Why the approach is sound.</strong> After applying the identical normalization pass to both trees, the
 * decision is made by {@link EqualsVisitor#equals(Node, Node)} — JavaParser's own structural equality, which ignores
 * source positions and stored tokens but is sensitive to node type, scalar properties (identifiers, literals,
 * operators, modifiers), and child structure. Comparing structure rather than a re-printed string is what makes the
 * parenthesis normalization sound: precedence lives in the tree shape, so unwrapping {@link EnclosedExpr} cannot make
 * two expressions with different precedence look equal. The printed canonical strings are used only to build a readable
 * failure message, never to decide equivalence.
 */
final class AstEquivalence {

    private AstEquivalence() {}

    /**
     * Compares two compilation units and returns an empty optional when they are equivalent modulo trivia, or a
     * human-readable description of the first divergence otherwise.
     *
     * <p>The description is built for a maintainer debugging a printer regression: it names whether imports or program
     * structure diverged and includes a minimized excerpt of the canonical re-prints around the first differing region,
     * not just "not equal".
     */
    static Optional<String> describeDifference(CompilationUnit input, CompilationUnit output) {
        CompilationUnit normalizedInput = normalize(input);
        CompilationUnit normalizedOutput = normalize(output);

        Optional<String> importDifference = describeImportDifference(normalizedInput, normalizedOutput);
        if (importDifference.isPresent()) {
            return importDifference;
        }

        if (EqualsVisitor.equals(normalizedInput, normalizedOutput)) {
            return Optional.empty();
        }
        return Optional.of(
            "formatted output is not AST-equivalent to the input (ignoring comments, whitespace, and "
                + "import order). First structural divergence:"
                + System.lineSeparator()
                + minimizedDiff(canonicalString(normalizedInput), canonicalString(normalizedOutput))
        );
    }

    /**
     * Returns {@code true} when the two units represent the same program modulo trivia.
     */
    static boolean equivalent(CompilationUnit input, CompilationUnit output) {
        return describeDifference(input, output).isEmpty();
    }

    /**
     * Clones the unit, removes all comments, and sorts imports into the formatter's canonical order.
     *
     * <p>Cloning keeps the caller's tree untouched. Sorting imports with the formatter's own comparator makes the
     * deliberate reorder cancel out while still exposing a dropped or duplicated import as a list-content change.
     */
    private static CompilationUnit normalize(CompilationUnit unit) {
        CompilationUnit clone = unit.clone();
        stripComments(clone);
        canonicalizePresentation(clone);
        clone.getImports().sort(ImportSortTransform.FORMATTER_IMPORT_ORDER);
        return clone;
    }

    /**
     * Rewrites presentation-only syntax that the formatter legitimately changes, identically on both trees.
     *
     * <p>Applying the exact same rewrites to input and output is what keeps these normalizations sound: a difference
     * the formatter introduced in one of these constructs is erased on both sides only if it was purely presentational
     * (the rewrite collapses it to the same canonical form); a genuine content change (e.g. a renamed identifier or a
     * different literal lexeme) survives because the canonical form still differs.
     *
     * <p>Text blocks are canonicalized the same way: both sides are replaced by a {@link StringLiteralExpr} of the
     * block's JLS-computed value (see the class Javadoc), so incidental re-indentation collapses to the same form while
     * a genuine content change survives — and a text block deleted or replaced by a non-text-block expression still
     * diverges structurally.
     */
    private static void canonicalizePresentation(CompilationUnit unit) {
        unit.accept(
            new ModifierVisitor<Void>() {
                @Override
                public Visitable visit(EnclosedExpr enclosed, Void arg) {
                    super.visit(enclosed, arg);
                    Expression inner = enclosed.getInner();
                    inner.remove();
                    return inner;
                }

                @Override
                public Visitable visit(LambdaExpr lambda, Void arg) {
                    super.visit(lambda, arg);
                    lambda.setEnclosingParameters(true);
                    return lambda;
                }

                @Override
                public Visitable visit(TextBlockLiteralExpr textBlock, Void arg) {
                    super.visit(textBlock, arg);
                    // Compare text blocks by their JLS-computed String value (incidental indentation removed, escapes
                    // translated): that value is program data the running program observes. The formatter is
                    // value-preserving for text blocks — TextBlockPrinter renders the source token verbatim — so
                    // re-indentation does not change the value, while any real content change does and must fail.
                    // Replacing the literal with a StringLiteralExpr of its resolved value (rather than dropping it)
                    // also keeps a text block deleted or replaced by a non-text-block expression structurally divergent.
                    return new StringLiteralExpr().setValue(textBlock.translateEscapes());
                }

                @Override
                public Visitable visit(EmptyStmt emptyStmt, Void arg) {
                    super.visit(emptyStmt, arg);
                    if (emptyStmt.getParentNode().filter(BlockStmt.class::isInstance).isPresent()) {
                        return null;
                    }
                    return emptyStmt;
                }
            },
            null
        );

        unit.stream()
                .filter(NodeWithModifiers.class::isInstance)
                .map(node -> (NodeWithModifiers<?>) node)
                .forEach(AstEquivalence::sortModifiers);
    }

    private static void sortModifiers(NodeWithModifiers<?> node) {
        NodeList<Modifier> modifiers = node.getModifiers();
        if (modifiers.size() < 2) {
            return;
        }
        modifiers.sort(Comparator.comparing(modifier -> modifier.getKeyword().asString()));
    }

    private static void stripComments(Node node) {
        node.setComment(null);
        for (Comment contained : node.getAllContainedComments()) {
            contained.remove();
        }
    }

    /**
     * Reports an import-level divergence as a multiset comparison so a dropped or duplicated import is named precisely.
     *
     * <p>Both lists are already canonically sorted by {@link #normalize}, so a positional walk over the printed import
     * declarations identifies the first added or missing import directly.
     */
    private static Optional<String> describeImportDifference(CompilationUnit input, CompilationUnit output) {
        List<String> inputImports = input.getImports().stream().map(AstEquivalence::printImport).sorted().toList();
        List<String> outputImports = output.getImports().stream().map(AstEquivalence::printImport).sorted().toList();
        if (inputImports.equals(outputImports)) {
            return Optional.empty();
        }
        String missing = firstAbsent(inputImports, outputImports);
        if (missing != null) {
            return Optional.of("import dropped by formatting: " + missing);
        }
        String added = firstAbsent(outputImports, inputImports);
        if (added != null) {
            return Optional.of("import added or duplicated by formatting: " + added);
        }
        return Optional.of(
            "import declarations differ between input and formatted output: input="
                + inputImports
                + " output="
                + outputImports
        );
    }

    /**
     * Returns the first entry present in {@code candidates} more often than in {@code reference}, or {@code null}.
     *
     * <p>Counting occurrences (rather than mere membership) is what lets a duplicated import be reported even though the
     * import name already exists in the other list.
     */
    private static String firstAbsent(List<String> candidates, List<String> reference) {
        Map<String, Long> referenceCounts = reference.stream()
                .collect(Collectors.groupingBy(entry -> entry, Collectors.counting()));
        Map<String, Long> seen = new HashMap<>();
        for (String candidate : candidates) {
            long seenSoFar = seen.merge(candidate, 1L, Long::sum);
            if (seenSoFar > referenceCounts.getOrDefault(candidate, 0L)) {
                return candidate;
            }
        }
        return null;
    }

    private static String printImport(ImportDeclaration declaration) {
        return (declaration.isStatic() ? "static " : "")
            + declaration.getNameAsString()
            + (declaration.isAsterisk() ? ".*" : "");
    }

    private static String canonicalString(CompilationUnit unit) {
        return printer().print(unit);
    }

    private static DefaultPrettyPrinter printer() {
        PrinterConfiguration configuration = new DefaultPrinterConfiguration()
                .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS))
                .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_JAVADOC));
        return new DefaultPrettyPrinter(configuration);
    }

    /**
     * Produces a compact line-oriented diff around the first differing line of the two canonical re-prints.
     */
    private static String minimizedDiff(String expected, String actual) {
        List<String> expectedLines = expected.lines().toList();
        List<String> actualLines = actual.lines().toList();
        int limit = Math.min(expectedLines.size(), actualLines.size());
        int firstDiff = 0;
        while (firstDiff < limit && expectedLines.get(firstDiff).equals(actualLines.get(firstDiff))) {
            firstDiff++;
        }
        int contextStart = Math.max(0, firstDiff - 2);
        StringBuilder builder = new StringBuilder();
        for (int line = contextStart; line < firstDiff; line++) {
            builder.append("  ").append(expectedLines.get(line)).append(System.lineSeparator());
        }
        builder.append("- input:  ")
                .append(firstDiff < expectedLines.size() ? expectedLines.get(firstDiff) : "<end of input>")
                .append(System.lineSeparator());
        builder.append("+ output: ")
                .append(firstDiff < actualLines.size() ? actualLines.get(firstDiff) : "<end of output>");
        return builder.toString();
    }
}
