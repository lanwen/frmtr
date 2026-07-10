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
                .doOnNext(outcome -> journalWriter.atInfo().addValue("frame", frame.toMap())
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

    FlowResult zippedDispatch(DispatchSource dispatchSource, String tenantKey, Packet packet) {
        return FlowResult.zip(
            dispatchSource.resolveTenant(tenantKey),
            dispatchSource.resolvePacket(packet.reference()),
            (tenant, packetName) -> buildDispatchEnvelope(
                tenant.routeName(),
                tenant.routeId(),
                packetName,
                packet.reference(),
                packet.window(),
                packet.startedAt(),
                DispatchSignal.START
            )
        );
    }

    FlowResult details(
            DispatchRepository dispatchRepository,
            ImageCounter imageCounter,
            Clock clock,
            String requestId
    ) {
        return dispatchRepository.findByIdWithOwner(requestId)
                .flatMap(record -> imageCounter.count(record).map(
                        counts -> DispatchDetails.from(clock, record, counts)
                ));
    }

    FlowResult imageCounts(CacheTemplate cacheTemplate, String imagesKey) {
        return cacheTemplate
                .<String, byte[]>opsForHash()
                .entries(imagesKey)
                .collectMap(
                    Map.Entry::getKey,
                    entry -> Long.parseLong(new String(entry.getValue(), StandardCharsets.UTF_8))
                );
    }

    GatewayPlan route(GatewayPlan plan, Resolver resolver) {
        return defaults(plan)
                .routeRules(rules -> rules.pathMatchers("/ready", "/ready/**", "/about").allow().pathMatchers("/**")
                            .guarded()
                )
                .tokenRelay(relay -> relay.managerResolver(resolver))
                .build();
    }

    ResponseSpec keepsSourceMultilineChainLambda(WebClient client, Map<String, String> query, String account) {
        return client.get()
                .uri(spec -> spec.path("/metrics/{account}/summary").queryParams(MultiValueMap.fromSingleValue(query))
                            .build(account)
                )
                .exchange();
    }

    RouteSpec keepsFluentLambdaBodyWithinLimit(RouteSpec route, LocalServices services, Set<String> hosts) {
        return route
                .header(HeaderNames.HOST, hostPattern(hosts))
                .and()
                .path(ServicePaths.ACCOUNTS)
                .filters(spec -> spec
                            .setRequestHeader(GatewayForwardedRequestHeaderFilter.HEADER_NAME, services.forwardedHost())
                            .filter(routeToEndpoint(route))
                )
                .uri(audienceUri(route));
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

    StubFlow recoversDuplicateMember(MemberRepository memberRepository, Member member, User user, Org org) {
        return memberRepository
                .save(new Member(org.getId(), user.getId(), Member.Role.ADMIN))
                .onErrorResume(
                    DuplicateKeyException.class,
                    ex -> memberRepository.findByUserIdAndOrganizationIdWithSnapshot(user.getId(), org.getId())
                );
    }

    StubFlow fallsThroughManagers(AuthToken authentication, List<ReactiveAuthenticationManager> managers, int index) {
        return managers.get(index)
                .authenticate(authentication)
                .onErrorResume(ex -> index + 1 < managers.size()
                        ? authenticate(authentication, managers, index + 1)
                        : Mono.error(ex)
                );
    }

    void rejectsInvalidEncodedKey() {
        assertThatThrownBy(() -> Keys.decode().es256(Base64.getEncoder().encodeToString("something hidden".getBytes())))
                .isInstanceOf(ParseException.class);
    }

    StubFlow repeatsUntilEvent(StubFlow source, EventConsumer consumer, Predicate<Event> filter) {
        return source.records()
                .filter(filter)
                .map(EventRecord::value)
                .doFinally(signal -> consumer.unsubscribe())
                .repeatWhen(emitted -> emitted.handle((last, sink) -> {
                        if (last > 0) {
                            sink.complete();
                            return;
                        }
                        sink.next(last);
                }))
                .doFinally(signal -> consumer.close());
    }

    ClientSpec keepsLastLambdaArgumentAttached(ClientSpec builder) {
        return builder
                .defaultStatusHandler(
                    StatusCode.NOT_FOUND::isSameCodeAs,
                    resp -> resp.releaseBody().ofType(Exception.class)
                )
                .filter(new FilterStep("alpha"));
    }

    StubFlow keepsLoggingBodyUnderLimit(StubFlow source, Logger log, String itemId) {
        return source.prepare()
                .then(source.expire(itemId, DEFAULT_TTL))
                .doOnError(error -> log.atError().addValue("item.id", itemId).log(
                        "Failed to persist buffered event for item",
                        error
                ))
                .then();
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
                .allMatch(row -> ((row.count() == 0 && row.day().isBefore(boundary.last()))
                        || (row.count() == 1 && row.day().isAfter(boundary.last()))
                        || (row.count() == 1 && row.day().isEqual(boundary.last())))
                )
                .filteredOn(row -> row.day().isBefore(boundary.last().plusDays(3))
                        && row.day().isAfter(boundary.last())
                );
    }

    Prefix keepsFirstLogicalOperandWithLambdaHeader(Variable variable, Options options, String declarationPrefix) {
        return variable.getInitializer()
                .filter(initializer -> initializer instanceof ArrayCreationExpr
                        || initializer instanceof BinaryExpr
                        || initializer instanceof CastExpr
                        || initializer instanceof ConditionalExpr
                        || initializer instanceof LambdaExpr
                        || initializer instanceof MethodCallExpr
                        || initializer instanceof ObjectCreationExpr
                        || initializer instanceof SwitchExpr
                )
                .map(ignored -> options.indentUnit() + declarationPrefix)
                .orElse("");
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
                )
                    .findSessions(principal.groupId(), Source.REMOTE, principal, null)
                )
                .expectSubscription();
    }

    StepProbe keepsMethodCallLambdaBodyPacked(StepProbe probe, SessionReader sessionReader, Principal principal) {
        return probe
                .withVirtualTime(() -> sessionReader.findSessions(principal.groupId(), Source.LOCAL, principal, null))
                .expectSubscription();
    }

    WindowRange keepsExceptionSupplierConstructorOpener(List<LedgerEntry> ledgerEntries) {
        WindowRange range = WindowRange.empty();
        for (LedgerEntry ledgerEntry : ledgerEntries) {
            WindowSpan ledgerSpan = spanFor(ledgerEntry).orElseThrow(
                () -> new IllegalArgumentException(
                    ledgerEntry.getClass().getSimpleName() + " is missing a layout span"
                )
            );
            range = range.cover(ledgerSpan);
        }
        return range;
    }

    FlowResult fillsMissingRows(UsageRepository usageRepository, String tenantId, List<LocalDate> windows) {
        return usageRepository.fetchRows(tenantId)
                .collectList()
                .map(knownRows -> windows.stream()
                        .map(window -> {
                            return knownRows.stream()
                                    .filter(row -> row.window().equals(window))
                                    .findFirst()
                                    .orElseGet(() -> WindowUsage.builder()
                                            .tenantId(tenantId)
                                            .window(window)
                                            .usage(UsageCount.EMPTY)
                                            .build()
                                    );
                        })
                        .collect(Collectors.toList())
                );
    }

    FlowResult fillsProjectedRows(
            ProjectionRepository projectionRepository,
            String tenantId,
            List<LocalDate> accountingWindows
    ) {
        return projectionRepository.fetchRows(tenantId)
                .collectList()
                .map(projectedRows -> accountingWindows.stream()
                        .map(accountingWindow -> {
                            return projectedRows.stream()
                                    .filter(
                                        projectedWindowUsage -> projectedWindowUsage.accountingWindow().equals(accountingWindow)
                                    )
                                    .findFirst()
                                    .orElseGet(() -> ProjectedWindowUsageSnapshot.builder().tenantId(tenantId)
                                                .accountingWindow(accountingWindow)
                                                .usage(UsageCount.EMPTY)
                                                .build()
                                    );
                        })
                        .collect(Collectors.toList())
                );
    }

    FlowResult combinesCounters(CounterStream counterStream) {
        return counterStream.grouped()
                .flatMapIterable(Map::values)
                .map(counters -> counters.stream()
                        .reduce((left, right) -> new ImageCounter(
                                left.projectedImageReference(),
                                left.projectedContainerCount() + right.projectedContainerCount()
                        ))
                        .orElseThrow()
                );
    }

    AuditTrail keepsFlatScopedCallBodyStable(AuditTrail auditTrail, Segment segment, Defaults defaults) {
        return auditTrail.record(
            segment.primaryKey(),
            segment.fallbackKey(),
            entry -> entry.routingContext().resolvedPolicy().composeWindowSelection(
                defaults.policyGroup(),
                entry.lastVisibleWindow(),
                segment.ownerGroup(),
                defaults.clock()
            )
        );
    }

    MatchPlan keepsFlatLogicalLambdaBodyStable(MatchPlan plan, Boundary boundary, Event event) {
        return plan.select(event.kind(), row ->
            (row.owner().equals(event.owner()) && row.createdAt().isAfter(boundary.openedAt()))
                || (row.priority() == Priority.FALLBACK && row.createdAt().isBefore(boundary.closedAt()))
        );
    }

    DraftPlan keepsFlatMethodCallArgumentBodyStable(DraftPlan plan, Window window, Cursor cursor, String label) {
        return plan.map(window.id(), cursor.next(), slot -> buildProjectionEnvelope(
                slot.currentVersion(),
                window.ownerKey(),
                cursor.traceToken(),
                label,
                slot.expiryPolicy()
        ));
    }

    RoutePlan keepsNestedLambdaArgumentBodyStable(RoutePlan plan, MarkerIndex markerIndex, String routeKey) {
        return plan.rewrite(routeKey, candidate -> markerIndex.find(
                candidate.ownerKey(),
                marker -> marker.visibleTo(candidate.viewer()) && marker.routeKey().equals(routeKey),
                candidate.defaultMarker()
        ));
    }
}
