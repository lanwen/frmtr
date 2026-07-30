class NotificationRouter {
    void rescheduleFailedDeliveries(NotificationBatch batch, DeliveryLedger ledger) {
        var retryCandidatesByRecipientAndChannel = pendingDeliveries.collectGroupedByOutcome((recipient, channel, outcome) -> {
            return outcome.isTransient() && recipient.prefersRetryOn(channel);
        });
        schedule(retryCandidatesByRecipientAndChannel);
    }
}
