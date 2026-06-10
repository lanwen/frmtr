package dev.lanwen.frmtr.tooling;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One rendered diagnostic line split into semantic spans.
 */
public record DiagnosticLine(List<DiagnosticSpan> spans) {
    public DiagnosticLine {
        Objects.requireNonNull(spans, "spans");
        spans = List.copyOf(spans);
    }

    public String plainText() {
        return spans.stream().map(DiagnosticSpan::text).collect(Collectors.joining());
    }
}
