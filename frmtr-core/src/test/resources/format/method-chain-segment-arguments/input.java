class MethodChainSegmentArgumentsSample {
    Result waitForSelection(Context context, Duration shortDelay, Duration totalDelay) {
        return Waiter.await()
            .pollInterval(shortDelay)
            .atMost(totalDelay)
            .until(
                () -> {
                    var entries = context.entries();
                    return entries.isEmpty() ? null : entries.getFirst();
                },
                Objects::nonNull
            );
    }

    Receipt acknowledgeRouteAccess(AccessRequest request) {
        return Flow.just(request.primaryToken())
            .map(this::decodeRouteToken)
            .onErrorMap(IllegalArgumentException.class, CredentialEnvelopeException::new)
            .flatMap(token -> {
                return scheduleLedger.authorizeRouteKey(request.getWindowId(), new Allocation.Owner(ROUTE_LEDGER_SOURCE), token);
            })
            .thenReturn(Receipt.empty());
    }

    void reportWorkerFailures(Job job) {
        if (includeStackTrace) {
            job.failedEntries().forEach(entry -> entry.failureCause().ifPresent(cause -> recordFailure(entry.displayPath().toString(), cause)));
            return;
        }
    }

    void inspectDecisionPath(DecisionReport report) {
        assertThat(report.branchSelection().visibleNodes()).singleElement().satisfies(node -> assertThat(node.decision())
            .isPresent());
    }

    void inspectConstructedDecision(DecisionReport report) {
        assertThat(report.branchSelection().visibleNodes()).singleElement().satisfies(node -> new DecisionProbe(node.decision())
            .isPresent());
    }

    void inspectShipmentPolicy(RoutePlan routePlan) {
        assertThat(routePlan.deliveryWindows()).singleElement().satisfies(deliveryWindow -> {
            assertThat(deliveryWindow.routePolicy().scope()).isEqualTo(ShipmentPlacementScope.AVAILABILITY_ZONE);
            assertThat(deliveryWindow.routePolicy().selectors())
                .containsEntry("region", "eu-central-1")
                .containsEntry("delivery-zone", "eu-central-1b");
        });
    }

    boolean hasExpressionLambdaBeforeFinalSegment(List<RouteCall> calls) {
        if (
            calls.stream()
                    .limit(Math.max(0, calls.size() - 1))
                    .anyMatch(this::methodCallSegmentHasExpressionLambdaArgument)
        ) {
            return true;
        }
        return false;
    }

    boolean expressionLambdaRootFits(Optional<Plan> plan, Call root, Width lineBudget, Options options) {
        return plan
                .map(plan -> plan.firstLineFits(
                        line -> compactRootLineWidth(root, line, lineBudget),
                        options.lineWidth()
                ))
                .orElse(true);
    }

    Doc fallbackScope(RouteCall expression) {
        return promotedFieldAccessRoot(expression)
                    .or(() -> expression.scope().map(scope -> Doc.concat(
                            expressionRenderer.apply(scope),
                            chainContinuation(methodCallChainSegment(expression))
                    )))
                    .orElseGet(() -> inlineMethodCall(expression));
    }

    Doc trailingComment(RouteCall expression, Optional<RouteCall> nextCall) {
        return nextCall
                .map(next -> trailingLineCommentBeforeNextSegment(expression, Optional.of(next)))
                .orElseGet(() -> finalTrailingLineComment(expression));
    }

    boolean sameRange(Token token, Region expected) {
        return token.getRange()
                .map(sourceText::region)
                .map(region -> region.beginOffset() == expected.beginOffset()
                    && region.endOffset() == expected.endOffset())
                .orElse(false);
    }

    boolean startsAfterName(Token token, Expression initializer) {
        return token.getRange()
                .flatMap(nameRange -> initializer.getRange().map(
                        initializerRange ->
                            initializerRange.begin.line > nameRange.end.line
                ))
                .orElse(false);
    }
}
