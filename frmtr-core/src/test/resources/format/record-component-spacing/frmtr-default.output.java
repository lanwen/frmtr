class RecordComponentSpacing {

    record EnvelopeWithManyCompactComponents(
        String alpha,

        String beta,
        String gamma,
        String delta,
        String epsilon,
        String zeta
    ) {}
}

public record Pet(@NotNull String name) {}

public record Pet(@NotNull String name, int age) {}

public record Pet(@NotNull String name, int age, String... nicknames, Object @Nullable... validationArgs) {
    public Pet {
        if (age < 0) {
            throw new IllegalArgumentException("Age cannot be negative");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be blank");
        }
    }

    public void test() {}
}

public record Pet(@NotNull String name, int age, String... nicknames, Object @Nullable... validationArgs) {}

public record Pet() {}

public record Pet() {
    public void test() {}
}

class T {

    String record = "active";

    void t() {
        record = "archived";
    }

    class MyRecordSimplifiedConstructor {

        record CustomerRecord(String name, int age) {
            public CustomerRecord {
                if (age < 0) {
                    throw new IllegalArgumentException("Age cannot be negative");
                }

                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Name cannot be blank");
                }
            }
        }
    }

    class MyRecordConstructor {

        record CustomerRecord(String name, int age) {
            public CustomerRecord(String name, int age) {
                if (age < 0) {
                    throw new IllegalArgumentException("Age cannot be negative");
                }
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Name cannot be blank");
                }
            }
        }
    }

    public class MyRecordWithAnnotationAndModifiers {

        public record CustomerRecord(String name, int age) {
            @Annotation
            @Annotation2
            public CustomerRecord {
                if (age < 0) {
                    throw new IllegalArgumentException("Age cannot be negative");
                }

                if (
                    name == null
                    || name.isBlank()
                ) {
                    throw new IllegalArgumentException("Name cannot be blank");
                }
            }
        }
    }
}

class MySplitRecordConstructor {

    record CustomerRecord(String name, int age, String name, int age, String name, int age) {
        public CustomerRecord(String name, int age) {
            if (age < 0) {
                throw new IllegalArgumentException("Age cannot be negative");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Name cannot be blank");
            }
        }
    }
}

public interface CustomerProjection {
    record CustomerRecord(String param) implements CustomerProjection {}
}

public interface CustomerProjection {
    record CustomerSplitRecord(
        String param,
        String param,
        String param,
        String param,
        String param,
        String param
    ) implements CustomerProjection {}
}

public record Record(
    @JsonSerialize(using = StatusSerializer.class, nullsUsing = NullSerializer.class)
    @Schema(type = "integer", description = "Some fancy description")
    Status status,

    @NotNull Integer sequenceNumber,

    Integer retryCount
) {}

public record Record(
    @JsonSerialize(using = StatusSerializer.class, nullsUsing = NullSerializer.class)
    @Schema(type = "integer", description = "Some fancy description")
    // comment
    Status status,
    // comment
    @NotNull Integer sequenceNumber
) {}

public record Record(
    @Schema(type = "integer", description = "A small description ") Status status,

    @Schema(type = "integer", description = "A longer description  ") Status status
) {}

record AuditEvent<EventId>(Payload payload) implements DomainEvent {
    void publish() {}
}

record AuditEvent<EventId, TenantId>(Payload payload) implements DomainEvent {
    void publish() {}
}

record AuditEvent<EventId, TenantId>(Payload payload) implements DomainEvent {}

record AuditEvent<EventId, TenantId, TraceId, RequestId, CorrelationId, RevisionId>(
    Payload payload
) implements DomainEvent {
    void publish() {}
}

record AuditEvent<EventId, TenantId, TraceId, RequestId, CorrelationId, RevisionId>(
    Payload payload
) implements DomainEvent {}

record AuditEvent<EventId, TenantId, TraceId, RequestId, CorrelationId, RevisionId>(
    Payload payload
) implements DomainEvent, TenantEvent, MeteredEvent, ArchivedEvent, ReplayableEvent, IndexedEvent {
    void publish() {}
}
