class MultilineIfConditionSample {
    boolean isOpenUnsupported(Throwable error) {
        var cause = error;
        while (cause != null) {
            if (
                cause instanceof StatusReply.ErrorMessage message &&
                StatusReplies.OPEN_UNAVAILABLE.equals(message.toString())
            ) {
                return true;
            }
            if (
                cause instanceof UnsupportedOperationException unsupported &&
                "open".equals(unsupported.getMessage())
            ) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    void settle(CaseState state, CaseEvent event, Interactions interactions) {
        if (ResultLedgerTransitionBindingsWithVerboseFixturePrefix.cleanupCompleteAfter(state.snapshot(), event.key(), event.binding())) {
            ResultLedgerInteractions.PendingTransitionWithExtremelyLongFixtureName transitionToComplete = interactions.drainPendingTransitions();
            complete(transitionToComplete);
        }
    }

    void flagPressure(EnvelopeMeta meta, List<Frame> frames) {
        if (
            ((meta.totalBudget() != null && Objects.equals(meta.usedBudget(), meta.totalBudget())) ||
                (meta.minimumRemaining() != null && meta.minimumRemaining() < MINIMUM_REMAINING)) ||
            frames.stream().anyMatch(frame -> frame.interruptedAt() != null)
        ) {
            sink.record();
        }
    }

    void guardActor(Object actor) {
        if (
            !(actor instanceof sample.security.identity.ServicePrincipal.AuthorizedActor.ProjectScopedDirectoryUserActor user)
        ) {
            return;
        }

        sink.record(user.id());
    }

    void screenRouteAnnotations(RouteAnnotation routeAnnotation) {
        if (!routeAnnotation.segmentPairs().stream()
                .map(RouteSegmentPair::candidate)
                .anyMatch(candidate -> candidate.hasRouteComments() || candidate.mustRebuildRoute())) {
            sink.record(routeAnnotation);
        }
    }

    void nestedRouteCheckpoint(Object checkpoint, WidthProbe widthProbe, RenderOptions options) {
        if (
            checkpoint instanceof RouteCheckpoint routeCheckpoint &&
            (routeCheckpoint.sourceSpansMultipleStops() || widthProbe.measure(routeCheckpoint.compactRouteName()) > options.lineWidth())
        ) {
            sink.record(routeCheckpoint);
        }
    }

    void rejectOverwideRoute(RouteBudget routeBudget, DeliveryRoute deliveryRoute, RouteLeg routeLeg, RenderOptions options) {
        if (routeBudget.estimatedTransferWidth(deliveryRoute.primaryStop(), routeLeg.compactSegmentName()) > options.lineWidth()) {
            sink.record(routeLeg);
        }
    }

    void publishFinishedMarker(Queue queue) {
        runner.attach(event -> {
            switch (event.kind()) {
                case DONE -> {
                    if (
                        event.markers().contains(Marker.PRIMARY_DONE) ||
                        event.markers().contains(Marker.SECONDARY_CONFIRMED)
                    ) {
                        queue.publish(event);
                    }
                }
                default -> {
                    queue.skip(event);
                }
            }
        });
    }
}
