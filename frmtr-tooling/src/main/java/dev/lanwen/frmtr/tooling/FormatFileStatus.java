package dev.lanwen.frmtr.tooling;

public enum FormatFileStatus {
    /**
     * The checked file already matches formatter output and does not need to be written.
     */
    UNCHANGED,
    /**
     * The checked file differs from formatter output and would change if formatting were applied.
     */
    CHANGED,
    /**
     * The formatter wrote updated output to the file during a formatting run.
     */
    WRITTEN,
    /**
     * The formatter reached the write step for a changed file, but writing failed; the file may have been partially updated.
     */
    WRITTEN_PARTIALLY,
    /**
     * The file could not be read, parsed, formatted, or written during the run.
     */
    FAILED,
}
