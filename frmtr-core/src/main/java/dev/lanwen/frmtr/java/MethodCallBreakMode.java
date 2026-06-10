package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.doc.Doc;

/**
 * Names whether a method-call argument or chain layout is caller-forced or width/comment-driven.
 */
enum MethodCallBreakMode {
    /** Let width and comment checks decide whether the call stays flat, groups softly, or becomes a chain. */
    AUTO,

    /** Preserve a caller-selected broken call shape because the surrounding expression already overflowed. */
    FORCED;

    static MethodCallBreakMode fromForced(boolean forced) {
        return forced ? FORCED : AUTO;
    }

    boolean isForced() {
        return this == FORCED;
    }

    Doc argumentLine() {
        return isForced() ? Doc.HARD_LINE : Doc.SOFT_LINE;
    }
}
