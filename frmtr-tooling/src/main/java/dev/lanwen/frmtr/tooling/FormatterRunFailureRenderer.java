package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders failed formatter runs and files with enough framing for tooling output.
 *
 * <p>This helper owns the run-level and file-level failure outline around formatter source diagnostics so adapters can
 * reuse both plain diagnostic text and structured diagnostic spans. It intentionally leaves stream selection, exit
 * handling, and terminal color mapping to callers.
 */
public final class FormatterRunFailureRenderer {

    private static final int MAX_MESSAGE_LINE_LENGTH = 80;

    private FormatterRunFailureRenderer() {}

    public static String render(FormatRunResult run) {
        return renderDiagnostic(run).plainText();
    }

    public static DiagnosticText renderDiagnostic(FormatRunResult run) {
        return run.failedResults()
                .stream()
                .map(FormatterRunFailureRenderer::renderDiagnostic)
                .collect(DiagnosticTextBuilder.joining(fileSeparator()));
    }

    public static String render(FormatFileResult result) {
        return renderDiagnostic(result).plainText();
    }

    public static DiagnosticText renderDiagnostic(FormatFileResult result) {
        Exception exception = result.failureException().orElseThrow();
        return outline(renderFailureTitle(exception), renderFailureBody(exception));
    }

    private static DiagnosticLine renderFailureTitle(Exception exception) {
        if (
            exception instanceof FormatterException formatterException
            && !formatterException.sourceProblems().isEmpty()
        ) {
            return diagnosticLine(span(formatterException.getMessage() + ":", DiagnosticStyle.ERROR_TEXT));
        }
        String message = exception.getMessage();
        return diagnosticLine(
            span(
                message == null || message.isBlank() ? exception.getClass().getSimpleName() : message,
                DiagnosticStyle.ERROR_TEXT
            )
        );
    }

    private static DiagnosticText renderFailureBody(Exception exception) {
        if (
            exception instanceof FormatterException formatterException
            && !formatterException.sourceProblems().isEmpty()
        ) {
            return renderFormatterException(formatterException);
        }
        if (exception instanceof FormatterException formatterException && formatterException.internal()) {
            return stacktraceHint();
        }
        return diagnosticText();
    }

    private static DiagnosticText stacktraceHint() {
        return diagnosticText(
            diagnosticLine(
                span("Run with --stacktrace to get more details.", DiagnosticStyle.ERROR_TEXT)
            )
        );
    }

    private static DiagnosticText renderFormatterException(FormatterException exception) {
        int lineNumberWidth = lineNumberWidth(exception.sourceProblems());
        return exception.sourceProblems()
                .stream()
                .map(problem -> diagnosticText(renderSourceProblem(problem, lineNumberWidth)))
                .collect(DiagnosticTextBuilder.joining(problemSeparator(lineNumberWidth)));
    }

    private static List<DiagnosticLine> renderSourceProblem(
            FormatterException.SourceProblem problem,
            int lineNumberWidth
    ) {
        List<DiagnosticLine> lines = new ArrayList<>();
        boolean messageRendered = false;
        Integer lastSourceLineNumber = null;
        for (FormatterException.SourceLine line : sourceLines(problem)) {
            if (lastSourceLineNumber != null && line.lineNumber() > lastSourceLineNumber + 1) {
                lines.add(gapLine(lineNumberWidth));
            }
            lines.add(sourceLine(lineNumberWidth, line));
            lastSourceLineNumber = line.lineNumber();
            if (
                problem.location().isPresent()
                && problem.location().orElseThrow().line() == line.lineNumber()
            ) {
                messageRendered = appendPointerAndMessage(lines, lineNumberWidth, line, problem);
            }
        }
        if (!messageRendered) {
            lines.add(diagnosticLine(span(problem.message(), DiagnosticStyle.ERROR_TEXT)));
        }
        return List.copyOf(lines);
    }

    private static List<FormatterException.SourceLine> sourceLines(FormatterException.SourceProblem problem) {
        List<FormatterException.SourceLine> lines = new ArrayList<>();
        problem.enclosingUnitLine().ifPresent(lines::add);
        problem.contextLines()
                .stream()
                .filter(line -> lines.stream().noneMatch(existing -> existing.lineNumber() == line.lineNumber()))
                .forEach(lines::add);
        lines.sort(Comparator.comparingInt(FormatterException.SourceLine::lineNumber));
        return List.copyOf(lines);
    }

    private static boolean appendPointerAndMessage(
            List<DiagnosticLine> lines,
            int lineNumberWidth,
            FormatterException.SourceLine line,
            FormatterException.SourceProblem problem
    ) {
        FormatterException.SourceLocation location = problem.location().orElseThrow();
        int pointerOffset = location.column() - line.startColumn();
        if (pointerOffset < 0 || pointerOffset > line.text().length()) {
            return false;
        }
        String gutter = " ".repeat(lineNumberWidth + 2);
        lines.add(
            diagnosticLine(
                span(gutter, DiagnosticStyle.BORDER_GUTTER),
                span(pointerLine(pointerOffset), DiagnosticStyle.POINTER)
            )
        );
        lines.add(diagnosticLine(span(gutter, DiagnosticStyle.BORDER_GUTTER), span("│", DiagnosticStyle.POINTER)));
        appendMessage(lines, gutter, problem.message());
        return true;
    }

    private static String pointerLine(int pointerOffset) {
        if (pointerOffset == 0) {
            return "^";
        }
        return "┌" + "─".repeat(pointerOffset - 1) + "^";
    }

    private static void appendMessage(List<DiagnosticLine> rendered, String gutter, String message) {
        List<String> lines = message.lines().toList();
        long contentLineCount = lines.stream().filter(line -> !line.isEmpty()).count();
        int contentLineIndex = 0;
        if (contentLineCount == 0) {
            rendered.add(
                diagnosticLine(
                    span(gutter, DiagnosticStyle.BORDER_GUTTER),
                    span("└─ ", DiagnosticStyle.POINTER)
                )
            );
            return;
        }
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty()) {
                rendered.add(
                    diagnosticLine(
                        span(gutter, DiagnosticStyle.BORDER_GUTTER),
                        span("│", DiagnosticStyle.POINTER)
                    )
                );
                continue;
            }
            contentLineIndex++;
            boolean lastContentLine = contentLineIndex == contentLineCount;
            List<String> wrapped = wrapMessageLine(line);
            for (int wrappedIndex = 0; wrappedIndex < wrapped.size(); wrappedIndex++) {
                rendered.add(
                    diagnosticLine(
                        span(gutter, DiagnosticStyle.BORDER_GUTTER),
                        span(
                            messageLinePrefix(contentLineCount, lastContentLine, wrappedIndex, wrapped.size()),
                            DiagnosticStyle.POINTER
                        ),
                        span(wrapped.get(wrappedIndex), DiagnosticStyle.ERROR_TEXT)
                    )
                );
            }
        }
    }

    private static List<String> wrapMessageLine(String line) {
        List<String> wrapped = new ArrayList<>();
        String remaining = line;
        while (remaining.length() > MAX_MESSAGE_LINE_LENGTH) {
            int breakAt = remaining.lastIndexOf(' ', MAX_MESSAGE_LINE_LENGTH);
            if (breakAt <= 0) {
                breakAt = MAX_MESSAGE_LINE_LENGTH;
            }
            wrapped.add(remaining.substring(0, breakAt).stripTrailing());
            remaining = remaining.substring(breakAt).stripLeading();
        }
        wrapped.add(remaining);
        return List.copyOf(wrapped);
    }

    private static String messageLinePrefix(
            long contentLineCount,
            boolean lastContentLine,
            int wrappedIndex,
            int wrappedLineCount
    ) {
        boolean onlyRenderedLine = contentLineCount == 1 && wrappedLineCount == 1;
        boolean lastRenderedLine = lastContentLine && wrappedIndex == wrappedLineCount - 1;
        if (onlyRenderedLine || lastRenderedLine) {
            return "└─ ";
        }
        return wrappedIndex == 0 ? "├─ " : "│  ";
    }

    private static int lineNumberWidth(List<FormatterException.SourceProblem> problems) {
        return problems.stream()
                .flatMap(problem -> sourceLines(problem).stream())
                .mapToInt(line -> Integer.toString(line.lineNumber()).length())
                .max()
                .orElse(0);
    }

    private static DiagnosticLine sourceLine(int lineNumberWidth, FormatterException.SourceLine line) {
        return diagnosticLine(
            span(String.format("%" + lineNumberWidth + "d", line.lineNumber()), DiagnosticStyle.LINE_NUMBER),
            span("  ", DiagnosticStyle.BORDER_GUTTER),
            span(line.text(), DiagnosticStyle.SOURCE_TEXT)
        );
    }

    private static DiagnosticLine gapLine(int lineNumberWidth) {
        String padding = " ".repeat(Math.max(0, lineNumberWidth - 1));
        if (padding.isEmpty()) {
            return diagnosticLine(span("⋮", DiagnosticStyle.GAP));
        }
        return diagnosticLine(span(padding, DiagnosticStyle.BORDER_GUTTER), span("⋮", DiagnosticStyle.GAP));
    }

    private static DiagnosticText outline(DiagnosticLine title, DiagnosticText body) {
        List<DiagnosticLine> lines = new ArrayList<>();
        List<DiagnosticSpan> headerSpans = new ArrayList<>();
        headerSpans.add(span("┌─ ", DiagnosticStyle.BORDER_GUTTER));
        headerSpans.addAll(title.spans());
        lines.add(new DiagnosticLine(headerSpans));
        if (body.plainText().isBlank()) {
            lines.add(diagnosticLine(span("└─", DiagnosticStyle.BORDER_GUTTER)));
            return diagnosticText(lines);
        }
        for (DiagnosticLine bodyLine : body.lines()) {
            if (bodyLine.plainText().isEmpty()) {
                lines.add(diagnosticLine(span("│", DiagnosticStyle.BORDER_GUTTER)));
                continue;
            }
            List<DiagnosticSpan> prefixedSpans = new ArrayList<>();
            prefixedSpans.add(span("│ ", DiagnosticStyle.BORDER_GUTTER));
            prefixedSpans.addAll(bodyLine.spans());
            lines.add(new DiagnosticLine(prefixedSpans));
        }
        lines.add(diagnosticLine(span("└─", DiagnosticStyle.BORDER_GUTTER)));
        return diagnosticText(lines);
    }

    private static List<DiagnosticLine> fileSeparator() {
        return List.of(diagnosticLine());
    }

    private static List<DiagnosticLine> problemSeparator(int lineNumberWidth) {
        return List.of(gapLine(lineNumberWidth));
    }

    private static DiagnosticText diagnosticText(DiagnosticLine... lines) {
        return diagnosticText(List.of(lines));
    }

    private static DiagnosticText diagnosticText(List<DiagnosticLine> lines) {
        return new DiagnosticText(lines);
    }

    private static DiagnosticLine diagnosticLine(DiagnosticSpan... spans) {
        return new DiagnosticLine(List.of(spans));
    }

    private static DiagnosticSpan span(String text, DiagnosticStyle style) {
        return new DiagnosticSpan(text, style);
    }

    private static final class DiagnosticTextBuilder {

        private final List<DiagnosticLine> lines = new ArrayList<>();

        private static java.util.stream.Collector<DiagnosticText, DiagnosticTextBuilder, DiagnosticText> joining(
                List<DiagnosticLine> separator
        ) {
            return java.util.stream.Collector.of(
                DiagnosticTextBuilder::new,
                (builder, diagnostic) -> builder.add(diagnostic, separator),
                (left, right) -> left.add(right.build(), separator),
                DiagnosticTextBuilder::build
            );
        }

        private DiagnosticTextBuilder add(DiagnosticText diagnostic, List<DiagnosticLine> separator) {
            if (!lines.isEmpty()) {
                lines.addAll(separator);
            }
            lines.addAll(diagnostic.lines());
            return this;
        }

        private DiagnosticText build() {
            return diagnosticText(lines);
        }
    }
}
