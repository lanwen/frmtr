package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class FormatterRunFailureRenderer {
    private FormatterRunFailureRenderer() {}

    public static String render(FormatRunResult run) {
        return run.failedResults().stream()
                .map(FormatterRunFailureRenderer::renderFailedFile)
                .collect(Collectors.joining(fileSeparator()));
    }

    private static String renderFailedFile(FormatFileResult result) {
        Exception exception = result.failureException().orElseThrow();
        return outline(renderFailureTitle(exception), renderFailureBody(exception));
    }

    private static String renderFailureTitle(Exception exception) {
        if (exception instanceof FormatterException formatterException
                && !formatterException.sourceProblems().isEmpty()) {
            return formatterException.getMessage() + ":";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String renderFailureBody(Exception exception) {
        if (exception instanceof FormatterException formatterException
                && !formatterException.sourceProblems().isEmpty()) {
            return renderFormatterException(formatterException);
        }
        return "";
    }

    private static String renderFormatterException(FormatterException exception) {
        int lineNumberWidth = lineNumberWidth(exception.sourceProblems());
        return exception.sourceProblems().stream()
                .map(problem -> renderSourceProblem(problem, lineNumberWidth))
                .collect(Collectors.joining(problemSeparator(lineNumberWidth)));
    }

    private static String renderSourceProblem(FormatterException.SourceProblem problem, int lineNumberWidth) {
        StringBuilder rendered = new StringBuilder();
        boolean messageRendered = false;
        Integer lastSourceLineNumber = null;
        for (FormatterException.SourceLine line : sourceLines(problem)) {
            if (lastSourceLineNumber != null && line.lineNumber() > lastSourceLineNumber + 1) {
                appendLine(rendered, gapLine(lineNumberWidth));
            }
            appendLine(rendered, sourceLine(lineNumberWidth, line));
            lastSourceLineNumber = line.lineNumber();
            if (problem.location().isPresent()
                    && problem.location().orElseThrow().line() == line.lineNumber()) {
                messageRendered = appendPointerAndMessage(rendered, lineNumberWidth, line, problem);
            }
        }
        if (!messageRendered) {
            appendLine(rendered, problem.message());
        }
        return rendered.toString();
    }

    private static List<FormatterException.SourceLine> sourceLines(FormatterException.SourceProblem problem) {
        List<FormatterException.SourceLine> lines = new ArrayList<>();
        problem.enclosingUnitLine().ifPresent(lines::add);
        problem.contextLines().stream()
                .filter(line -> lines.stream().noneMatch(existing -> existing.lineNumber() == line.lineNumber()))
                .forEach(lines::add);
        lines.sort(Comparator.comparingInt(FormatterException.SourceLine::lineNumber));
        return List.copyOf(lines);
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
        String gutter = " ".repeat(lineNumberWidth + 2);
        appendLine(rendered, gutter + "┌" + "─".repeat(pointerOffset) + "^");
        appendLine(rendered, gutter + "│");
        appendMessage(rendered, gutter, problem.message());
        return true;
    }

    private static void appendMessage(StringBuilder rendered, String gutter, String message) {
        List<String> lines = message.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            appendLine(rendered, gutter + messagePrefix(index, lines.size(), lines.get(index)));
        }
    }

    private static String messagePrefix(int index, int lineCount, String line) {
        if (lineCount == 1) {
            return "└─ " + line;
        }
        if (index == 0) {
            return "├─ " + line;
        }
        if (index == lineCount - 1) {
            return "└─ " + line;
        }
        return line.isEmpty() ? "│" : "│  " + line;
    }

    private static int lineNumberWidth(List<FormatterException.SourceProblem> problems) {
        return problems.stream()
                .flatMap(problem -> sourceLines(problem).stream())
                .mapToInt(line -> Integer.toString(line.lineNumber()).length())
                .max()
                .orElse(0);
    }

    private static String sourceLine(int lineNumberWidth, FormatterException.SourceLine line) {
        return String.format("%" + lineNumberWidth + "d  %s", line.lineNumber(), line.text());
    }

    private static String gapLine(int lineNumberWidth) {
        return String.format("%" + lineNumberWidth + "s", "⋮");
    }

    private static String outline(String title, String body) {
        String header = "┌─ " + title;
        if (body.isBlank()) {
            return header + System.lineSeparator() + "└─";
        }
        return header
                + System.lineSeparator()
                + body.lines()
                        .map(line -> line.isEmpty() ? "│" : "│ " + line)
                        .collect(Collectors.joining(System.lineSeparator()))
                + System.lineSeparator()
                + "└─";
    }

    private static void appendLine(StringBuilder rendered, String line) {
        if (!rendered.isEmpty()) {
            rendered.append(System.lineSeparator());
        }
        rendered.append(line);
    }

    private static String fileSeparator() {
        return System.lineSeparator() + System.lineSeparator();
    }

    private static String problemSeparator(int lineNumberWidth) {
        return System.lineSeparator()
                + gapLine(lineNumberWidth)
                + System.lineSeparator();
    }
}
