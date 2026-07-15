package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Reconstructs source-shaped text-block arguments without guessing surrounding syntax from raw line text.
 *
 * <p>This helper owns the indentation recovery for a text block that is the only argument to a method call whose call is
 * itself part of an expression-lambda body. The boundary exists because text-block literal contents remain owned by
 * {@link TextBlockPrinter}, while call and lambda printers only need a stable way to place that literal as an argument
 * without scanning the original line for {@code ->}.
 *
 * <p>Callers still decide when text-block arguments should be isolated from their call prefix, whether comments are
 * printable through the normal argument path, and how the surrounding call closes.
 */
final class TextBlockArgumentSourceLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final SourceText sourceText;

    private final FormatterOptions options;

    private final Function<TextBlockLiteralExpr, String> literalRenderer;

    TextBlockArgumentSourceLayout(
            SourceShapePolicy sourceShapePolicy,
            SourceText sourceText,
            FormatterOptions options,
            Function<TextBlockLiteralExpr, String> literalRenderer
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.sourceText = sourceText;
        this.options = options;
        this.literalRenderer = literalRenderer;
    }

    boolean methodCallIsExpressionLambdaBody(MethodCallExpr expression) {
        Node child = expression;
        Optional<Node> parent = expression.getParentNode();
        while (parent.isPresent()) {
            Node current = parent.orElseThrow();
            if (current instanceof LambdaExpr lambda) {
                Node bodyChild = child;
                return lambda.getExpressionBody().filter(body -> body == bodyChild).isPresent();
            }
            child = current;
            parent = current.getParentNode();
        }
        return false;
    }

    Doc expressionLambdaMethodCallArgument(TextBlockLiteralExpr textBlockLiteralExpr) {
        String literal = literalRenderer.apply(textBlockLiteralExpr);
        return Doc.text(options.lineEnding().value() + sourcePrefix(textBlockLiteralExpr, literal) + literal);
    }

    /**
     * Places a source-multiline text-block argument under its original lambda-body indentation.
     *
     * <p>The rendered literal already contains the formatter-owned text-block indentation. This method subtracts that
     * literal indentation from the original line prefix so source-multiline lambda calls do not double-indent the
     * opening {@code """}.
     */
    Doc expressionLambdaSourceMultilineArgument(TextBlockLiteralExpr textBlockLiteralExpr) {
        String literal = literalRenderer.apply(textBlockLiteralExpr);
        return Doc.text(
            options.lineEnding().value()
                + sourcePrefixBeforeLiteralIndent(textBlockLiteralExpr, literal)
                + literal
        );
    }

    Doc expressionLambdaMethodCallBodyArguments(
            MethodCallExpr methodCall,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList
    ) {
        if (
            methodCall.getArguments().size() == 1
            && methodCall.getArgument(0) instanceof TextBlockLiteralExpr textBlockLiteralExpr
            && !sourceShapePolicy.hasContainedComments(methodCall)
        ) {
            return expressionLambdaMethodCallArgument(textBlockLiteralExpr);
        }
        return Doc.indent(
            Doc.concat(
                Doc.HARD_LINE,
                methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)
            )
        );
    }

    /**
     * Returns only the source prefix that belongs before the formatted text-block literal.
     *
     * <p>{@link TextBlockPrinter} renders the literal with its own leading horizontal whitespace plus two formatter
     * indents for lambda-body argument placement. Removing that known literal prefix leaves the caller-owned source
     * indentation that should remain before the text block.
     */
    private String sourcePrefixBeforeLiteralIndent(TextBlockLiteralExpr textBlockLiteralExpr, String literal) {
        String sourcePrefix = sourcePrefix(textBlockLiteralExpr, literal);
        int literalPrefixLength = leadingHorizontalWhitespaceLength(literal) + (options.indentUnit().length() * 2);
        if (literalPrefixLength >= sourcePrefix.length()) {
            return "";
        }
        return sourcePrefix.substring(0, sourcePrefix.length() - literalPrefixLength);
    }

    /**
     * Returns indentation before a text-block opener without copying unrelated code from a collapsed source line.
     *
     * <p>When the opener starts on an indentation-only source line, that indentation is the source-shaped placement the
     * caller wants. Whitespace-perturbed inputs can instead put the opener after earlier statements on the same line;
     * in that case the closing delimiter's indentation is the closest source-owned text-block placement that is still
     * independent of the surrounding collapsed code.
     */
    private String sourcePrefix(TextBlockLiteralExpr textBlockLiteralExpr, String literal) {
        String prefix = textBlockLiteralExpr.getRange()
                .map(range -> sourceText.linePrefix(range.begin))
                .orElse("");
        return isHorizontalWhitespace(prefix) ? prefix : closingDelimiterIndent(literal);
    }

    private boolean isHorizontalWhitespace(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current != ' ' && current != '\t') {
                return false;
            }
        }
        return true;
    }

    private String closingDelimiterIndent(String literal) {
        int closingLineStart = literal.lastIndexOf('\n');
        if (closingLineStart < 0) {
            return "";
        }
        int indentStart = closingLineStart + 1;
        int indentEnd = indentStart;
        while (indentEnd < literal.length()) {
            char current = literal.charAt(indentEnd);
            if (current != ' ' && current != '\t') {
                break;
            }
            indentEnd++;
        }
        return literal.substring(indentStart, indentEnd);
    }

    private int leadingHorizontalWhitespaceLength(String text) {
        int length = 0;
        while (length < text.length()) {
            char current = text.charAt(length);
            if (current != ' ' && current != '\t') {
                break;
            }
            length++;
        }
        return length;
    }
}
