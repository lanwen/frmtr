public class FrameRouter {

    enum LinkState {
        HANDSHAKING,
        ESTABLISHED,
        DRAINING,
    }

    enum RequestKind {
        PRODUCE,
        FETCH,
        LIST_OFFSETS,
        METADATA,
        OFFSET_COMMIT,
        OFFSET_FETCH,
        FIND_COORDINATOR,
        JOIN_GROUP,
        DESCRIBE_GROUPS,
        LIST_GROUPS,
        SASL_HANDSHAKE,
        API_VERSIONS,
        CREATE_TOPICS,
        DELETE_TOPICS,
        HEARTBEAT,
    }

    RoutingDecision route(
            LinkState linkState,
            RequestKind requestKind,
            RequestContext request,
            FrameRegistry registry
    ) {
        return switch (linkState) {
            case ESTABLISHED -> {
                yield switch (requestKind) {
                    case PRODUCE, FETCH, LIST_OFFSETS, METADATA, OFFSET_COMMIT, OFFSET_FETCH, FIND_COORDINATOR,
                            JOIN_GROUP -> {
                        yield RoutingDecision.forwarded(requestKind);
                    }
                    case DESCRIBE_GROUPS, LIST_GROUPS, SASL_HANDSHAKE, API_VERSIONS, CREATE_TOPICS, DELETE_TOPICS -> registry.dispatch(
                        request
                    );
                    case HEARTBEAT -> RoutingDecision.local();
                    default -> RoutingDecision.rejected();
                };
            }
            default -> RoutingDecision.rejected();
        };
    }

    record RoutingDecision(String label) {
        static RoutingDecision forwarded(RequestKind requestKind) {
            return new RoutingDecision("forwarded:" + requestKind);
        }

        static RoutingDecision local() {
            return new RoutingDecision("local");
        }

        static RoutingDecision rejected() {
            return new RoutingDecision("rejected");
        }
    }

    record RequestContext() {}

    static final class FrameRegistry {

        RoutingDecision dispatch(RequestContext request) {
            return new RoutingDecision("dispatched");
        }
    }
}
