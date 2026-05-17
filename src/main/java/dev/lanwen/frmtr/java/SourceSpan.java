package dev.lanwen.frmtr.java;

public record SourceSpan(int beginLine, int beginColumn, int endLine, int endColumn) {
    public static SourceSpan unknown() {
        return new SourceSpan(-1, -1, -1, -1);
    }
}
