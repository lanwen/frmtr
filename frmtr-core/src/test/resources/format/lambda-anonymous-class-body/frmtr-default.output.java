package sample;

import java.util.Map;
import java.util.function.Supplier;

final class LambdaAnonymousClassBody {

    void breaksAnonymousBodyWhenConstructorArgumentsBreak(GatewayRegistry registry) {
        registry.registerProbe(
            () -> new DefaultConnectivityProbe("primary-verifiable-probe", routingContext()) {
                @Override
                public ProbeResult evaluate(ProbeScope scope, Map<String, Object> attributes) {
                    return ProbeResultBuilder.withStatusAndScope(ProbeResult.Status.OK, scope).build();
                }
            }
        );
    }

    void keepsCompactAnonymousBodyWhenConstructorArgumentsFit(GatewayRegistry registry) {
        registry.registerProbe(() -> new ShortProbe("p") { @Override public int score() { return 1; } });
    }

    Supplier<DefaultConnectivityProbe> assignmentControl() {
        DefaultConnectivityProbe probe = new DefaultConnectivityProbe("primary-verifiable-probe", routingContext()) {
            @Override
            public ProbeResult evaluate(ProbeScope scope, Map<String, Object> attributes) {
                return ProbeResultBuilder.withStatusAndScope(ProbeResult.Status.OK, scope).build();
            }
        };
        return () -> probe;
    }

    void directArgumentControl(GatewayRegistry registry) {
        registry.registerProbe(new DefaultConnectivityProbe("primary-verifiable-probe", routingContext()) {
            @Override
            public ProbeResult evaluate(ProbeScope scope, Map<String, Object> attributes) {
                return ProbeResultBuilder.withStatusAndScope(ProbeResult.Status.OK, scope).build();
            }
        });
    }

    RoutingContext routingContext() {
        return null;
    }

    interface GatewayRegistry {
        void registerProbe(Supplier<?> probeSupplier);

        void registerProbe(DefaultConnectivityProbe probe);
    }

    interface RoutingContext {}

    static class ShortProbe {

        ShortProbe(String name) {}

        public int score() {
            return 0;
        }
    }

    static class DefaultConnectivityProbe {

        DefaultConnectivityProbe(String name, RoutingContext context) {}

        public ProbeResult evaluate(ProbeScope scope, Map<String, Object> attributes) {
            return null;
        }
    }

    static class ProbeResult {

        enum Status {
            OK,
        }
    }

    enum ProbeScope {
        CONNECTIVITY,
    }

    static class ProbeResultBuilder {

        static ProbeResultBuilder withStatusAndScope(ProbeResult.Status status, ProbeScope scope) {
            return null;
        }

        ProbeResult build() {
            return null;
        }
    }
}
