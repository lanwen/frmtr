package dev.lanwen.frmtr.tooling;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Structured diagnostic output that preserves the existing plain text shape
 * while exposing semantic spans for adapter-specific rendering.
 */
public record DiagnosticText(List<DiagnosticLine> lines) {
    public DiagnosticText {
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
    }

    public String plainText() {
        return lines.stream().map(DiagnosticLine::plainText).collect(Collectors.joining(System.lineSeparator()));
    }
}
