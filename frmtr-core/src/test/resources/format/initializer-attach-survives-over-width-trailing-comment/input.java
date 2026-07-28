class NotificationDispatcher {
    void dispatch() {
        var notificationDeliveryChannel = channelRegistrationService.lookup(tenantId, channelName).activate(); // requires ack from downstream within 5 seconds
    }
}
