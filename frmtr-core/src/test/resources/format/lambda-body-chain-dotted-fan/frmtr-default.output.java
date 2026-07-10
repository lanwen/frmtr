package sample;

final class LambdaBodyChainDottedFan {

    void fansOverWidthBareRootChainByDots(RouteVerifier verifier) {
        verifier.assertEachRoute(handler -> assertThat(handler)
                .extracting(HandlerConfig::identifier)
                .containsOnly("primaryValue")
        );
    }

    void keepsFittingBareRootChainFlat(RouteVerifier verifier) {
        verifier.each(route -> assertThat(route).extracting(Route::id).containsOnly("green"));
    }

    void fansScopedRootChainInCanonicalFan(RouteVerifier verifier) {
        verifier.assertEachRoute(handler -> journalWriter.atInfo()
                .addValue("handler", handler.identifier())
                .log("checked handler")
        );
    }

    List<VotersEndpoint> keepsObjectCreationRootedLambdaBodyPacked(Map<ListenerName, InetSocketAddress> listeners) {
        return listeners.entrySet()
                .stream()
                .map(listener -> new VotersEndpoint()
                        .setName(listener.getKey().value())
                        .setHost(listener.getValue().getHostString())
                )
                .collect(Collectors.toList());
    }

    List<KafkaMetric> keepsChainSelectorHostedLambdaBodyPacked(Metrics metrics) {
        return metrics.metrics()
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey().description().contains(
                        "The number of active connections for this listener"
                ))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }
}
