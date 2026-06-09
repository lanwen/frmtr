package dev.lanwen.frmtr.java;

/**
 * Identifies a half-open region in one original Java source string.
 *
 * <p>This value owns formatter-internal character offsets together with the source line and column span used in debug
 * labels. The boundary exists because recovered raw islands need exact substring offsets, while JavaParser exposes
 * source positions as one-based line and column coordinates.
 *
 * <p>Callers still decide whether a region is safe to recover, how comments inside it are accounted, and how any raw
 * slice is rendered into formatter docs.
 */
record SourceRegion(
        int beginOffset,
        int endOffset,
        int beginLine,
        int beginColumn,
        int endLine,
        int endColumn) {
    SourceRegion {
        if (beginOffset < 0) {
            throw new IllegalArgumentException("beginOffset must be non-negative");
        }
        if (endOffset < beginOffset) {
            throw new IllegalArgumentException("endOffset must be greater than or equal to beginOffset");
        }
        if (beginLine < 1 || beginColumn < 1 || endLine < 1 || endColumn < 1) {
            throw new IllegalArgumentException("source line and column values must be one-based");
        }
    }

    /**
     * Returns a debug-oriented line and column range for locating this source region.
     */
    String lineColumnLabel() {
        return "line %d, column %d to line %d, column %d"
                .formatted(beginLine, beginColumn, endLine, endColumn);
    }
}
