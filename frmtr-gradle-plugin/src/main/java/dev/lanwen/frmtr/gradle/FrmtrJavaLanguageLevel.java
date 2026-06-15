package dev.lanwen.frmtr.gradle;

public enum FrmtrJavaLanguageLevel {
    /**
     * Infers the parser language level from Gradle Java configuration, using sourceCompatibility first and then the
     * Java toolchain language version.
     */
    AUTO,
    /**
     * Uses the bundled JavaParser dependency's bleeding-edge parser mode, regardless of the Gradle project target.
     */
    LATEST_AVAILABLE,
    /**
     * Leaves JavaParser's language level unset, selecting raw parser mode without release-specific feature gates.
     */
    UNDEFINED,
}
