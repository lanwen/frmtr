package sample;

final class LambdaArgumentLayouts {

    void conditionalBody(Stream<Order> orders) {
        orders.map(order -> order.isExpedited()
                ? expeditedHandlerFactory.create(order)
                : standardHandlerFactory.create(order)
        );
    }

    void objectCreationBody(Stream<Account> accounts) {
        accounts.map(account -> new AccountSummaryProjection(
                account.identifier(),
                account.displayName(),
                account.tierLevel()
        ));
    }

    void logicalBinaryBody(Stream<Visitor> visitors) {
        visitors.filter(visitor -> visitor.hasConfirmedEmailAddress()
                && visitor.acceptedLatestTermsOfService()
                && visitor.isWithinTrialWindow()
        );
    }

    void nestedLambdaBody(Aggregator aggregator) {
        aggregator.reduce(
            (accumulatedRunningTotal, incomingMeasurement) ->
                finalizer -> finalizer.combine(
                    accumulatedRunningTotal,
                    incomingMeasurement
                )
        );
    }

    void twoLambdaArguments(Pipeline pipeline) {
        pipeline.zip(
            leftIncomingItem -> leftIncomingItem.normalizeForCrossSourceComparison(),
            rightIncomingItem -> rightIncomingItem.normalizeForCrossSourceComparison()
        );
    }
}
