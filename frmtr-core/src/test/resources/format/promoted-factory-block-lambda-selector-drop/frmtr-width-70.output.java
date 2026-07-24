class MessageRouter {

    void wire() {
        Object subscription = dispatcher.subscribe(
            TypedHandler.forChannel(notificationChannel)
                    .onMessage(envelope -> {
                        telemetry.emit(
                            "subscription.notification-channel.delivery-acknowledged.audit-trail"
                        );
                        return Acknowledgement.of(
                            envelope.sequenceNumber()
                        );
                    })
        );
    }
}
