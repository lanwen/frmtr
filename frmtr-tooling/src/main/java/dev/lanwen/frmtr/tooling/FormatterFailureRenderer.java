package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import java.util.stream.Collectors;

public final class FormatterFailureRenderer {
    private FormatterFailureRenderer() {}

    public static String render(Exception exception) {
        if (exception instanceof FormatterException formatterException
                && !formatterException.sourceProblems().isEmpty()) {
            return renderFormatterException(formatterException);
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String renderFormatterException(FormatterException exception) {
        return exception.getMessage()
                + ":"
                + System.lineSeparator()
                + exception.sourceProblems().stream()
                        .map(FormatterFailureRenderer::renderSourceProblem)
                        .collect(Collectors.joining(parseProblemSeparator()));
    }

    private static String renderSourceProblem(FormatterException.SourceProblem problem) {
        StringBuilder rendered = new StringBuilder();
        int lineNumberWidth = lineNumberWidth(problem);
        problem.enclosingUnitLine()
                .filter(unitLine -> problem.contextLines().stream()
                        .noneMatch(contextLine -> contextLine.lineNumber() == unitLine.lineNumber()))
                .ifPresent(unitLine -> appendSourceLine(rendered, lineNumberWidth, unitLine));
        boolean messageRendered = false;
        for (FormatterException.SourceLine line : problem.contextLines()) {
            appendSourceLine(rendered, lineNumberWidth, line);
            if (problem.location().isPresent()
                    && problem.location().orElseThrow().line() == line.lineNumber()) {
                messageRendered = appendPointerAndMessage(rendered, lineNumberWidth, line, problem);
            }
        }
        if (!messageRendered) {
            appendLineSeparatorIfNeeded(rendered);
            rendered.append(problem.message());
        }
        return rendered.toString();
    }

    private static int lineNumberWidth(FormatterException.SourceProblem problem) {
        int contextWidth = problem.contextLines().stream()
                .mapToInt(line -> Integer.toString(line.lineNumber()).length())
                .max()
                .orElse(0);
        int unitWidth = problem.enclosingUnitLine()
                .map(line -> Integer.toString(line.lineNumber()).length())
                .orElse(0);
        return Math.max(contextWidth, unitWidth);
    }

    private static void appendSourceLine(
            StringBuilder rendered, int lineNumberWidth, FormatterException.SourceLine line) {
        appendLineSeparatorIfNeeded(rendered);
        rendered.append(String.format("%" + lineNumberWidth + "d  %s", line.lineNumber(), line.text()));
    }

    private static boolean appendPointerAndMessage(
            StringBuilder rendered,
            int lineNumberWidth,
            FormatterException.SourceLine line,
            FormatterException.SourceProblem problem) {
        FormatterException.SourceLocation location = problem.location().orElseThrow();
        int pointerOffset = location.column() - line.startColumn();
        if (pointerOffset < 0 || pointerOffset > line.text().length()) {
            return false;
        }
        rendered.append(System.lineSeparator())
                .repeat(" ", lineNumberWidth + 2)
                .repeat("-", pointerOffset)
                .append("^")
                .append(System.lineSeparator())
                .append(problem.message());
        return true;
    }

    private static void appendLineSeparatorIfNeeded(StringBuilder rendered) {
        if (!rendered.isEmpty()) {
            rendered.append(System.lineSeparator());
        }
    }

    private static String parseProblemSeparator() {
        return System.lineSeparator()
                + System.lineSeparator()
                + "// ..."
                + System.lineSeparator()
                + System.lineSeparator();
    }
}
