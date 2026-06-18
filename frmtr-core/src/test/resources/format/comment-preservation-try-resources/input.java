class TryResourceCommentSample {
    void run(Network network, Database database, Auth auth) {
        try (
            Service service = new Service(network, database);
            // keep resource note one
            // keep resource note two
            Zone zone = new Zone(service, auth)
                // keep chained resource note one
                // keep chained resource note two
                .withProperty("retry", "60s")
                .withMinimumRunningDuration(Duration.ZERO)
        ) {
            zone.start();
        }
    }

    void sentinelFence() {
        try ( // resource scope {
            ManagedResource resource = new ManagedResource("sample")
            // }
        ) {
            resource.start();
        }
    }
}

class Network {}

class Database {}

class Auth {}

class Service implements AutoCloseable {
    Service(Network network, Database database) {}
    public void close() {}
}

class ManagedResource implements AutoCloseable {
    ManagedResource(String name) {}
    void start() {}
    public void close() {}
}

class Zone implements AutoCloseable {
    Zone(Service service, Auth auth) {}
    Zone withProperty(String key, String value) { return this; }
    Zone withMinimumRunningDuration(Duration duration) { return this; }
    void start() {}
    public void close() {}
}

class Duration {
    static final Duration ZERO = new Duration();
}
