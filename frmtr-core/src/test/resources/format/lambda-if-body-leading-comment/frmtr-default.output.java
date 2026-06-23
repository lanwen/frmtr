class RouteSupervisor {

    boolean restart(RouteHandle handle, int attempt) {
        return scheduler.submit(() -> {
            if (!engine().isRunAllowed()) {
                // engine is shutting down so do not attempt to restart the route
                log.info("Restarting route: {} attempt: {} ...", handle.id(), attempt);
                return true;
            }
            return false;
        });
    }
}
