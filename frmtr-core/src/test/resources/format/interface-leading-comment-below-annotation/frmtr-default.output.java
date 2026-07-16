package dev.example.pipeline;

@Deprecated
/*
 * Retained until the streaming rewrite lands; new code should target StageContext instead.
 */
public interface ProcessorContext {
    String applicationId();

    void register(final StateStore store, final StateRestoreCallback callback);
}
