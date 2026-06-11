package sample;

import java.util.List;

final class LambdaExpressionArgumentOpener {

    private final MeshCatalog meshCatalog;

    private final JournalWriter journalWriter;

    FlowResult run(Packet packet, Frame frame, List<String> requestedMarks) {
        return meshCatalog
            .find(packet.accountKey())
            .map(MeshEntry::from)
            .flatMap(entry ->
                meshCatalog.prepareTransitTicketEnvelope(
                    entry,
                    MeshEntry.from(packet.forwardedSender()),
                    requestedMarks,
                    frame.toTransitEnvelope()
                )
            )
            .doOnNext(outcome ->
                journalWriter
                    .atInfo()
                    .addValue("frame", frame.toMap())
                    .addValue("packet", packet.sender())
                    .addValue("matched", outcome.getOrDefault("route", false))
                    .log("Recorded transit decision")
            )
            .switchIfEmpty(FlowResult.empty());
    }

    FlowResult denied(Frame frame, int limit, long used) {
        return FlowResult.failed(() ->
            meshCatalog.stopSignal(
                frame,
                StopReason.QUOTA_FULL,
                new CounterSnapshot(limit, used)
            )
        );
    }

    GatewayPlan route(GatewayPlan plan, Resolver resolver) {
        return defaults(plan)
            .routeRules(rules ->
                rules.pathMatchers("/ready", "/ready/**", "/about").allow().pathMatchers("/**").guarded()
            )
            .tokenRelay(relay -> relay.managerResolver(resolver))
            .build();
    }
}
