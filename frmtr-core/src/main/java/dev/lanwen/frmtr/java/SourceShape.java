package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.Optional;

/**
 * Detects original source line shapes that structured printers should preserve when choosing between equivalent docs.
 *
 * <p>This helper owns source-only questions such as "did this node span more than one line?" or "was the argument list
 * already multiline?" The boundary exists so layout printers can keep their syntax-specific rendering decisions while
 * asking one shared source-shape helper before collapsing a user's existing multiline form.
 */
final class SourceShape {
    private final RawSource rawSource;
    private final SourceText sourceText;

    SourceShape(RawSource rawSource, SourceText sourceText) {
        this.rawSource = rawSource;
        this.sourceText = sourceText;
    }

    /**
     * Reports whether the node's own source spans more than one line.
     */
    boolean spansMultipleLines(Node node) {
        return node.getRange()
                .map(range -> range.begin.line < range.end.line)
                .orElseGet(() -> rawSource.rawWithoutOwnComment(node).contains("\n"));
    }

    /**
     * Reports whether two nodes begin on the same source line.
     */
    boolean startsOnSameLine(Node left, Node right) {
        return left.getRange()
                .flatMap(leftRange -> right.getRange().map(rightRange -> leftRange.begin.line == rightRange.begin.line))
                .orElse(false);
    }

    /**
     * Reports whether a method call's argument list, excluding the receiver and selector, was already multiline.
     */
    boolean methodCallArgumentsSpanMultipleLines(MethodCallExpr expression) {
        return argumentsSpanMultipleLines(
                expression.getName(),
                expression.getArguments(),
                expression.getRange());
    }

    /**
     * Reports whether an expression-lambda argument starts on the same source line as the method-call selector.
     */
    boolean expressionLambdaStartsOnSelectorLine(MethodCallExpr expression) {
        Optional<Integer> selectorLine = expression.getName().getRange().map(range -> range.begin.line);
        if (selectorLine.isEmpty()) {
            return false;
        }
        return expression.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambda -> lambda.getExpressionBody().isPresent())
                .flatMap(lambda -> lambda.getRange().stream())
                .anyMatch(range -> range.begin.line == selectorLine.orElseThrow());
    }

    /**
     * Reports whether a constructor call's argument list was already multiline.
     */
    boolean objectCreationArgumentsSpanMultipleLines(ObjectCreationExpr expression) {
        return argumentsSpanMultipleLines(
                expression.getType(),
                expression.getArguments(),
                expression.getAnonymousClassBody().isPresent() ? Optional.empty() : expression.getRange());
    }

    /**
     * Reports whether a callable declaration's parameter list was already multiline.
     */
    boolean callableParametersSpanMultipleLines(CallableDeclaration<?> declaration) {
        NodeList<Parameter> parameters = declaration.getParameters();
        if (parameters.isEmpty()) {
            return false;
        }
        Optional<Range> nameRange = declaration.getName().getRange();
        Optional<Range> firstParameterRange = firstRange(parameters);
        if (nameRange.isEmpty() || firstParameterRange.isEmpty()) {
            return false;
        }
        if (firstParameterRange.orElseThrow().begin.line > nameRange.orElseThrow().end.line) {
            return true;
        }
        return entriesStartOnMultipleLines(parameters);
    }

    /**
     * Reports whether a method's throws keyword starts its own source line.
     */
    boolean throwsStartsOnOwnLine(CallableDeclaration<?> declaration) {
        Optional<Range> firstExceptionRange = declaration.getThrownExceptions().stream()
                .findFirst()
                .flatMap(Node::getRange);
        if (firstExceptionRange.isEmpty()) {
            return false;
        }
        String linePrefix = sourceText.linePrefix(firstExceptionRange.orElseThrow().begin);
        String trimmedPrefix = linePrefix.stripLeading();
        return trimmedPrefix.startsWith("throws")
                && trimmedPrefix.substring("throws".length()).isBlank();
    }

    /**
     * Describes source-only try-with-resources section shape after resources have been parsed.
     */
    TryResourcesShape tryResources(TryStmt statement) {
        if (statement.getResources().isEmpty()) {
            return new TryResourcesShape(false, false);
        }
        Optional<Range> statementRange = statement.getRange();
        Optional<Range> firstResourceRange = firstRange(statement.getResources());
        Optional<Range> lastResourceRange = lastRange(statement.getResources());
        Optional<Range> blockRange = statement.getTryBlock().getRange();
        if (statementRange.isEmpty()
                || firstResourceRange.isEmpty()
                || lastResourceRange.isEmpty()
                || blockRange.isEmpty()) {
            return new TryResourcesShape(false, false);
        }
        boolean spansMultipleLines = firstResourceRange.orElseThrow().begin.line > statementRange.orElseThrow().begin.line
                || nodesSpanMultipleLines(statement.getResources())
                || lastResourceRange.orElseThrow().end.line < blockRange.orElseThrow().begin.line;
        boolean trailingSemicolon = sourceText
                .sliceBetween(lastResourceRange.orElseThrow(), blockRange.orElseThrow())
                .stripLeading()
                .startsWith(";");
        return new TryResourcesShape(spansMultipleLines, trailingSemicolon);
    }

    private boolean argumentsSpanMultipleLines(
            Node prefix,
            NodeList<? extends Node> arguments,
            Optional<Range> containingRange) {
        if (arguments.isEmpty()) {
            return false;
        }
        Optional<Range> prefixRange = prefix.getRange();
        Optional<Range> firstArgumentRange = firstRange(arguments);
        Optional<Range> lastArgumentRange = lastRange(arguments);
        if (prefixRange.isEmpty() || firstArgumentRange.isEmpty() || lastArgumentRange.isEmpty()) {
            return false;
        }
        if (firstArgumentRange.orElseThrow().begin.line > prefixRange.orElseThrow().end.line) {
            return true;
        }
        if (entriesStartOnMultipleLines(arguments)) {
            return true;
        }
        return containingRange
                .map(range -> sourceText.sliceAfterWithin(lastArgumentRange.orElseThrow(), range).contains("\n"))
                .orElse(false);
    }

    private boolean nodesSpanMultipleLines(NodeList<? extends Node> nodes) {
        Optional<Range> first = firstRange(nodes);
        Optional<Range> last = lastRange(nodes);
        return first.isPresent()
                && last.isPresent()
                && first.orElseThrow().begin.line < last.orElseThrow().end.line;
    }

    private boolean entriesStartOnMultipleLines(NodeList<? extends Node> nodes) {
        Optional<Range> first = firstRange(nodes);
        if (first.isEmpty()) {
            return false;
        }
        int firstLine = first.orElseThrow().begin.line;
        return nodes.stream()
                .skip(1)
                .flatMap(node -> node.getRange().stream())
                .anyMatch(range -> range.begin.line > firstLine);
    }

    private Optional<Range> firstRange(NodeList<? extends Node> nodes) {
        return nodes.isEmpty() ? Optional.empty() : nodes.get(0).getRange();
    }

    private Optional<Range> lastRange(NodeList<? extends Node> nodes) {
        return nodes.isEmpty() ? Optional.empty() : nodes.get(nodes.size() - 1).getRange();
    }

    record TryResourcesShape(boolean spansMultipleLines, boolean trailingSemicolon) {}
}
