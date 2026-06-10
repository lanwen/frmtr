package dev.lanwen.frmtr.tooling;

import java.util.Objects;

/**
 * A contiguous segment of diagnostic text that shares one semantic style.
 */
public record DiagnosticSpan(String text, DiagnosticStyle style) {
    public DiagnosticSpan {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
    }
}
