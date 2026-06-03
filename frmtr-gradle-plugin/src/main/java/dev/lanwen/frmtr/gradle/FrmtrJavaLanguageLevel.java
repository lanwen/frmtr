package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.FormatterOptions;

public enum FrmtrJavaLanguageLevel {
    /**
     * Leaves JavaParser's language level unset, which selects raw parser mode without release-specific feature gates.
     */
    UNSET,
    /**
     * Uses the newest stable Java language level exposed by the bundled JavaParser dependency.
     */
    LATEST_AVAILABLE,
    JAVA_8,
    JAVA_9,
    JAVA_10,
    JAVA_11,
    JAVA_12,
    JAVA_13,
    JAVA_14,
    JAVA_15,
    JAVA_16,
    JAVA_17,
    JAVA_18,
    JAVA_19,
    JAVA_20,
    JAVA_21,
    JAVA_22,
    JAVA_23,
    JAVA_24,
    JAVA_25;

    FormatterOptions.JavaLanguageLevel toFormatterOptions() {
        return FormatterOptions.JavaLanguageLevel.valueOf(name());
    }
}
