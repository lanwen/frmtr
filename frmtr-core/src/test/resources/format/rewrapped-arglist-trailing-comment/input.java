package dev.lanwen.fixtures.topology;

class StateCleanup {

    void deleteState(String topologyName) {
        throw new IllegalStateException("Unable to delete state for the named topology " + topologyName,
                                        new RuntimeException(topologyName)); // use dummy taskid for this error
    }

    void deleteStateWidthDriven(String topologyName) {
        throw new IllegalStateException("Unable to delete state for the named topology " + topologyName, new RuntimeException(topologyName)); // width-driven re-wrap keeps the note
    }

    void reportCleanup(String topologyName, EventSink sink) {
        sink.recordCleanupFailure("Unable to delete state for the named topology " + topologyName,
                                  new RuntimeException(topologyName)); // call re-wrap keeps the note
    }

    void reportCleanupWidthDriven(String topologyName, EventSink sink) {
        sink.recordCleanupFailure("Unable to delete state for the named topology " + topologyName, new RuntimeException(topologyName)); // width-driven call note
    }

    void compactThrowKeepsNote(String topologyName) {
        throw new IllegalStateException(topologyName); // compact throw note stays inline
    }

    void compactCallKeepsNote(EventSink sink) {
        sink.flush(1, 2); // compact call note stays inline
    }

    interface EventSink {

        void recordCleanupFailure(String message, RuntimeException cause);

        void flush(int first, int second);
    }
}
