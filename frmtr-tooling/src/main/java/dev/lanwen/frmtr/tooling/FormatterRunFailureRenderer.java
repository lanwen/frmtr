package dev.lanwen.frmtr.tooling;

import java.util.stream.Collectors;

public final class FormatterRunFailureRenderer {
    private FormatterRunFailureRenderer() {}

    public static String render(FormatRunResult run) {
        return run.failedResults().stream()
                .map(FormatterRunFailureRenderer::renderFailedFile)
                .collect(Collectors.joining(fileSeparator()));
    }

    private static String renderFailedFile(FormatFileResult result) {
        return result.displayPath()
                + System.lineSeparator()
                + indent(FormatterFailureRenderer.render(result.failureException().orElseThrow()));
    }

    private static String indent(String text) {
        return text.lines()
                .map(line -> "  " + line)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static String fileSeparator() {
        return System.lineSeparator() + System.lineSeparator();
    }
}
