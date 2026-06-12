class Synchronized {
    void doSomething() {
        synchronized (this.lock) {
            flushPendingUpdates();
        }
    }
}