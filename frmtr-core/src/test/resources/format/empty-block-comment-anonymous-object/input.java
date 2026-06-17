interface SharedWorkerFactory {
    SharedWorker SHARED = new SharedWorkerImpl(false, null, Set.of(), null) {
        @Override
        public void close() {
            // keep shared worker open for coordinator cleanup
        }
    };
}
