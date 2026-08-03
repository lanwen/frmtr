package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.LambdaExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders lambda parameter text and the header fragment before a lambda arrow.
 *
 * <p>This helper owns the canonical lambda parameter spelling used by lambda expression rendering and call-layout
 * helpers: parenthesis policy, source-comment reconstruction inside parameter lists, source-multiline parameter
 * detection, and width-triggered header breaks. The boundary exists so callers can choose the surrounding body or call
 * shape without duplicating lambda syntax decisions.
 *
 * <p>Callers still decide whether the lambda is rendered as an expression body, a block body, or a method-call
 * argument. This helper only returns parameter/header text or docs for the lambda syntax before {@code ->}.
 */
final class LambdaParameterHeaderLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ToIntFunction<String> currentIndentedWidth;

    LambdaParameterHeaderLayout(
            SourceShapePolicy sourceShapePolicy,
            RawSource rawSource,
            FormatterOptions options,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.rawSource = rawSource;
        this.options = options;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    String parameters(LambdaExpr expression) {
        if (expression.getParameters().size() != 1) {
            return "(" + compactJoin.apply(expression.getParameters()) + ")";
        }
        String parameter = compact.apply(expression.getParameters().get(0));
        if (options.lambdaArrowParens() == FormatterOptions.LambdaArrowParens.ALWAYS) {
            return "(" + parameter + ")";
        }
        if (
            options.lambdaArrowParens() == FormatterOptions.LambdaArrowParens.AVOID
            && parameterCanAvoidParens(expression)
        ) {
            return parameter;
        }
        return expression.isEnclosingParameters() ? "(" + parameter + ")" : parameter;
    }

    boolean shouldBreak(LambdaExpr expression, String flatParameters) {
        return expression.getParameters().size() > 1
            && currentIndentedWidth.applyAsInt(flatParameters + " -> {}") > options.lineWidth();
    }

    boolean haveComments(LambdaExpr expression) {
        return parameterText(expression)
                .map(parameterText -> parameterText.contains("//") || parameterText.contains("/*"))
                .orElseGet(() -> expression.getParameters().stream().anyMatch(sourceShapePolicy::hasContainedComments));
    }

    Optional<String> inlineCommentedLambda(LambdaExpr expression) {
        if (expression.getComment().isPresent() || expression.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        return parameterText(expression)
                .filter(parameterText -> parameterText.contains("/*"))
                .filter(parameterText -> !parameterText.contains("//"))
                .flatMap(this::compactInlineCommentedParameters)
                .map(parameters -> parameters + " -> " + compact.apply(expression.getExpressionBody().orElseThrow()));
    }

    Doc forHeader(LambdaExpr expression, String flatParameters) {
        return forHeader(expression, flatParameters, false);
    }

    Doc sourceMultilineForHeader(LambdaExpr expression) {
        return forHeader(expression, parameters(expression), true);
    }

    boolean hasSourceMultilineParameters(LambdaExpr expression) {
        return parameterText(expression).filter(parameterText -> parameterText.contains("\n")).isPresent();
    }

    private Doc forHeader(
            LambdaExpr expression,
            String flatParameters,
            boolean forceBreak
    ) {
        if (haveComments(expression)) {
            return commentedForHeader(expression);
        }
        if (forceBreak) {
            return brokenHeader(expression);
        }
        // Single-param bare lambdas stay flat: breaking adds parens that flip isEnclosingParameters()
        // in the next pass, which changes chain-layout decisions and causes oscillation.
        if (expression.getParameters().size() <= 1) {
            return Doc.text(flatParameters);
        }
        // Rank flat vs broken at the true rendered column, reserving room for ` -> {` on the same line.
        return Doc.reserving(
            Doc.conditionalGroup(List.of(Doc.text(flatParameters), brokenHeader(expression))),
            " -> {".length()
        );
    }

    private Doc brokenHeader(LambdaExpr expression) {
        return Doc.concat(
            Doc.text("("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        expression.getParameters()
                                .stream()
                                .map(parameter -> Doc.text(compact.apply(parameter)))
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Doc commentedForHeader(LambdaExpr expression) {
        return Doc.concat(
            Doc.text("("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.HARD_LINE,
                        commentedParameterLines(expression)
                                .stream()
                                .map(Doc::text)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    /**
     * Reconstructs commented lambda parameters from the raw token text before {@code ->}.
     *
     * <p>JavaParser does not expose comments inside the parameter list as separator-level trivia. The formatter reads
     * the original parameter text, strips the outer parentheses when present, and then splits comma-separated parameters
     * while keeping line and block comments on the line where the source placed them.
     */
    private List<String> commentedParameterLines(LambdaExpr expression) {
        String parameterText = parameterText(expression).orElseGet(
            () -> compactJoin.apply(expression.getParameters())
        );
        if (parameterText.startsWith("(") && parameterText.endsWith(")")) {
            parameterText = parameterText.substring(1, parameterText.length() - 1);
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : parameterText.lines().map(String::strip).toList()) {
            if (rawLine.isEmpty()) {
                continue;
            }
            addCommentedParameterLine(lines, rawLine);
        }
        return lines;
    }

    private void addCommentedParameterLine(List<String> lines, String rawLine) {
        int lineComment = rawLine.indexOf("//");
        if (lineComment >= 0) {
            String beforeComment = rawLine.substring(0, lineComment).stripTrailing();
            String comment = rawLine.substring(lineComment).stripTrailing();
            if (beforeComment.isBlank()) {
                lines.add(comment);
                return;
            }
            addCommaSeparatedParameters(lines, beforeComment, comment);
            return;
        }
        if (rawLine.startsWith("/*")) {
            lines.add(rawLine);
            return;
        }
        addCommaSeparatedParameters(lines, rawLine, "");
    }

    private void addCommaSeparatedParameters(List<String> lines, String text, String trailingComment) {
        boolean lineEndsWithComma = text.stripTrailing().endsWith(",");
        String[] parameters = text.split(",");
        for (int i = 0; i < parameters.length; i++) {
            String parameter = parameters[i].strip();
            if (parameter.isEmpty()) {
                continue;
            }
            boolean last = i == parameters.length - 1;
            if (!last) {
                lines.add(parameter + ",");
            } else if (!trailingComment.isBlank()) {
                lines.add(parameter + (lineEndsWithComma ? ", " : " ") + trailingComment);
            } else {
                lines.add(parameter + (lineEndsWithComma ? "," : ""));
            }
        }
    }

    private Optional<String> parameterText(LambdaExpr expression) {
        return expression.getTokenRange()
                .map(Object::toString)
                .filter(raw -> raw.contains("->"))
                .map(raw -> raw.substring(0, raw.indexOf("->")).strip());
    }

    private Optional<String> compactInlineCommentedParameters(String parameterText) {
        List<String> lines = parameterText.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("/*")) {
                return Optional.empty();
            }
        }
        return Optional.of(
            rawSource.normalizeWhitespace(String.join(" ", lines))
                    .replace("( /*", "(/*")
                    .replaceAll(",\\s*", ", ")
                    .replaceAll("\\s+\\)", ")")
        );
    }

    private boolean parameterCanAvoidParens(LambdaExpr expression) {
        return expression.getParameters().size() == 1
            && expression.getParameters().get(0).getAnnotations().isEmpty()
            && expression.getParameters().get(0).getModifiers().isEmpty()
            && expression.getParameters().get(0).getType().isUnknownType();
    }
}
