package sample;

import java.util.List;

final class LambdaExpressionArgumentOpener {

    private final MeshCatalog meshCatalog;

    private final JournalWriter journalWriter;

    FlowResult run(Packet packet, Frame frame, List<String> requestedMarks) {
        return meshCatalog
            .find(packet.accountKey())
            .map(MeshEntry::from)
            .flatMap(entry -> meshCatalog.prepareTransitTicketEnvelope(
                    entry,
                    MeshEntry.from(packet.forwardedSender()),
                    requestedMarks,
                    frame.toTransitEnvelope()
            ))
            .doOnNext(outcome -> journalWriter
                    .atInfo()
                    .addValue("frame", frame.toMap())
                    .addValue("packet", packet.sender())
                    .addValue("matched", outcome.getOrDefault("route", false))
                    .log("Recorded transit decision")
            )
            .switchIfEmpty(FlowResult.empty());
    }

    FlowResult denied(Frame frame, int limit, long used) {
        return FlowResult.failed(() -> meshCatalog.stopSignal(
                frame,
                StopReason.QUOTA_FULL,
                new CounterSnapshot(limit, used)
        ));
    }

    GatewayPlan route(GatewayPlan plan, Resolver resolver) {
        return defaults(plan)
            .routeRules(
                rules -> rules.pathMatchers("/ready", "/ready/**", "/about").allow().pathMatchers("/**").guarded()
            )
            .tokenRelay(relay -> relay.managerResolver(resolver))
            .build();
    }

    StubFlow answerWithRepositoryCall(StubSource stubSource, BundleGateway regionalWindowBundleReadGateway) {
        return when(
            stubSource.fetchPreparedEnvelope(
                "north-window-ticket",
                "south-window-ticket",
                "east-window-ticket",
                "west-window-ticket"
            )
        ).thenAnswer(invocation -> regionalWindowBundleReadGateway.findFirstLaunchBundlesForWindowTickets(
                invocation.getArgument(0),
                invocation.getArgument(1)
        ));
    }

    StubFlow answerWithLongBodySelector(StubSource stubSource, BundleGateway regionalWindowBundleReadGateway) {
        return when(
            stubSource.fetchPreparedEnvelope(
                "north-window-ticket",
                "south-window-ticket",
                "east-window-ticket",
                "west-window-ticket"
            )
        ).thenAnswer(invocation -> regionalWindowBundleReadGateway
            .findFirstLaunchBundlesForWindowTicketsWithVerifiedProjectionState(
                invocation.getArgument(0),
                invocation.getArgument(1)
        ));
    }

    void keepsCommaBeforeLineComment(EventSink sink, Event event, String owner) {
        sink.publish(
            true,
            event.mark("alpha"),
            event.mark("beta"),
            event.mark("outside").owner("other"), // keep marker explanation
            event.mark("inside").owner(owner)
        );
    }

    ChainResult keepsLogicalLambdaBodiesBroken(ChainProbe probe, Ledger ledger, DayBoundary boundary) {
        return probe
            .rows(ledger.rows())
            .allMatch(row ->
                ((row.count() == 0 && row.day().isBefore(boundary.last())) ||
                    (row.count() == 1 && row.day().isAfter(boundary.last())) ||
                    (row.count() == 1 && row.day().isEqual(boundary.last())))
            )
            .filteredOn(row ->
                row.day().isBefore(boundary.last().plusDays(3)) &&
                    row.day().isAfter(boundary.last())
            );
    }

    StepProbe keepsConstructorLambdaBodyPacked(
        StepProbe probe,
        PacketRepository packetRepository,
        EventJournal eventJournal,
        RemoteReader remoteReader,
        Clock clock,
        DatabaseClient databaseClient,
        AgentLedger agentLedger,
        DirectoryClient directoryClient,
        Principal principal
    ) {
        return probe
            .withVirtualTime(() -> new SessionReader(
                    packetRepository,
                    eventJournal,
                    remoteReader,
                    clock,
                    databaseClient,
                    agentLedger,
                    directoryClient
                ).findSessions(principal.groupId(), Source.REMOTE, principal, null)
            )
            .expectSubscription();
    }

    StepProbe keepsMethodCallLambdaBodyPacked(StepProbe probe, SessionReader sessionReader, Principal principal) {
        return probe
            .withVirtualTime(() -> sessionReader.findSessions(principal.groupId(), Source.LOCAL, principal, null))
            .expectSubscription();
    }
}
