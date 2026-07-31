class DeliveryNotifier {

    void wire() {
        deliveryGateway.subscribeForUpdates(
            update -> {
                metrics.recordDelivery(
                    update.channel()
                );
                return Acknowledgement.confirmed();
            }
        );
    }
}
