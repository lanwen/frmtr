package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import java.util.ArrayList;
import java.util.List;

public final class FormatterFailureRenderer {
    private FormatterFailureRenderer() {}

    public static String render(Exception exception) {
        return renderDiagnostic(exception).plainText();
    }

    public static DiagnosticText renderDiagnostic(Exception exception) {
        if (exception instanceof FormatterException formatterException
                && !formatterException.sourceProblems().isEmpty()) {
            return renderFormatterException(formatterException);
        }
        String message = exception.getMessage();
        return diagnosticText(errorLine(message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
    }

    private static DiagnosticText renderFormatterException(FormatterException exception) {
        List<DiagnosticLine> lines = new ArrayList<>();
        lines.add(errorLine(exception.getMessage() + ":"));
        for (int index = 0; index < exception.sourceProblems().size(); index++) {
            if (index > 0) {
                lines.addAll(parseProblemSeparator());
            }
            lines.addAll(renderSourceProblem(exception.sourceProblems().get(index)));
        }
        return diagnosticText(lines);
    }

    private static List<DiagnosticLine> renderSourceProblem(FormatterException.SourceProblem problem) {
        List<DiagnosticLine> lines = new ArrayList<>();
        int lineNumberWidth = lineNumberWidth(problem);
        problem.enclosingUnitLine()
                .filter(unitLine -> problem.contextLines().stream()
                        .noneMatch(contextLine -> contextLine.lineNumber() == unitLine.lineNumber()))
                .ifPresent(unitLine -> lines.add(sourceLine(lineNumberWidth, unitLine)));
        boolean messageRendered = false;
        for (FormatterException.SourceLine line : problem.contextLines()) {
            lines.add(sourceLine(lineNumberWidth, line));
            if (problem.location().isPresent()
                    && problem.location().orElseThrow().line() == line.lineNumber()) {
                messageRendered = appendPointerAndMessage(lines, lineNumberWidth, line, problem);
            }
        }
        if (!messageRendered) {
            lines.add(errorLine(problem.message()));
        }
        return List.copyOf(lines);
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

    private static DiagnosticLine sourceLine(int lineNumberWidth, FormatterException.SourceLine line) {
        return diagnosticLine(
                span(String.format("%" + lineNumberWidth + "d", line.lineNumber()), DiagnosticStyle.LINE_NUMBER),
                span("  ", DiagnosticStyle.BORDER_GUTTER),
                span(line.text(), DiagnosticStyle.SOURCE_TEXT));
    }

    private static boolean appendPointerAndMessage(
            List<DiagnosticLine> lines,
            int lineNumberWidth,
            FormatterException.SourceLine line,
            FormatterException.SourceProblem problem) {
        FormatterException.SourceLocation location = problem.location().orElseThrow();
        int pointerOffset = location.column() - line.startColumn();
        if (pointerOffset < 0 || pointerOffset > line.text().length()) {
            return false;
        }
        lines.add(diagnosticLine(
                span(" ".repeat(lineNumberWidth + 2), DiagnosticStyle.BORDER_GUTTER),
                span("-".repeat(pointerOffset) + "^", DiagnosticStyle.POINTER)));
        lines.add(errorLine(problem.message()));
        return true;
    }

    private static List<DiagnosticLine> parseProblemSeparator() {
        return List.of(blankLine(), diagnosticLine(span("// ...", DiagnosticStyle.GAP)), blankLine());
    }

    private static DiagnosticText diagnosticText(DiagnosticLine... lines) {
        return diagnosticText(List.of(lines));
    }

    private static DiagnosticText diagnosticText(List<DiagnosticLine> lines) {
        return new DiagnosticText(lines);
    }

    private static DiagnosticLine errorLine(String text) {
        return diagnosticLine(span(text, DiagnosticStyle.ERROR_TEXT));
    }

    private static DiagnosticLine blankLine() {
        return diagnosticLine();
    }

    private static DiagnosticLine diagnosticLine(DiagnosticSpan... spans) {
        return new DiagnosticLine(List.of(spans));
    }

    private static DiagnosticSpan span(String text, DiagnosticStyle style) {
        return new DiagnosticSpan(text, style);
    }
}
