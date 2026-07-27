class SupportEscalationTracker {

    void track() {
        var interactionRecordForCustomerSupportEscalation =
            supportDesk.registerInteraction(customerId, agentId).finalize();
    }
}
