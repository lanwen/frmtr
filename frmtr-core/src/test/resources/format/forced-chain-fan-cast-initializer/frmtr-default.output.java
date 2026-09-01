class ForcedChainFanCastInitializer {

    void deliveryScheduleFlatSource(OrderEvent orderEvent, String regionCode, CarrierService carrierService) {
        DeliverySchedule schedule = (DeliverySchedule) orderEvent.validateOrderAndInventory()
                .deliveryPlanForDistributionRegion(regionCode)
                .scheduleDelivery(carrierService);
    }

    void deliveryScheduleFannedSource(OrderEvent orderEvent, String regionCode, CarrierService carrierService) {
        DeliverySchedule schedule = (DeliverySchedule) orderEvent.validateOrderAndInventory()
                .deliveryPlanForDistributionRegion(regionCode)
                .scheduleDelivery(carrierService);
    }
}
