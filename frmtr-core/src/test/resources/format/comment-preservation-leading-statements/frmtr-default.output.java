class LeadingStatementCommentSample {

    void parse(InputStream stream) {
        // keep first note
        // keep second note
        try {
            stream.read();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    boolean stopWhenClosed(ParseSession session, int attempt) {
        if (!session.isOpen()) {
            // Planner is shutting down, so do not schedule another pass.
            session.log("Restarting task: {} attempt: {} is cancelled before dispatch", session.id(), attempt);
            return true;
        }
        return false;
    }

    void scopedRouteRefresh(RouteTask task) {
        // Keep the temporary block visible while route state is reloaded.
        // The following setup note belongs inside the block body.
        {
            // Refresh before the lease check so stale route ids are ignored.
            task.refresh();
        }
    }

    void configure(ConfigProperties properties, String name) {
        OrderedProperties selected = Helper.extractProperties(
            properties,
            name + "[",
            "]",
            true,
            key -> {
                // Keep bracketed keys so collection entries remain visible.
                // This lets the caller remove only the configured prefix.
                // Dotted keys keep their ordinary lookup path.
                if (key.startsWith(name + "[")) {
                    return key.substring(name.length());
                }
                return key;
            }
        );
        selected.apply();
    }
}

interface InputStream {
    int read() throws Exception;
}
