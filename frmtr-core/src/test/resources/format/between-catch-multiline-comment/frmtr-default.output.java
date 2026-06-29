class BetweenCatch {

    void run() {
        try {
            work();
        } catch (RuntimeException e) {
            recover(e);
        } catch (Error err) {
            // Future developers: if you discover any other
            // rare-but-nonfatal exceptions, catch them here, and log as
            // above.
            log(err);
        }
    }

    void work() {}

    void recover(RuntimeException e) {}

    void log(Throwable t) {}
}
