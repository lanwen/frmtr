package dev.lanwen.frmtr.tooling;

/**
 * Receives progress snapshots for a multi-file formatter run.
 */
@FunctionalInterface
public interface FormatRunProgress {

    void progress(ProgressSnapshot state);
}
