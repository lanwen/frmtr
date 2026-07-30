class ShipmentTrackingCatalog {

    enum FulfillmentStage {
        RECEIVED,
        PACKED,
        SHIPPED;

        enum ShipmentMilestone implements Comparable<ShipmentMilestone>, ShipmentAuditable, WarehouseInventoryNotifiable {
            DEPARTED,
            ARRIVED,
            DELIVERED;

            @Override
            public int compareTo(ShipmentMilestone other) {
                return ordinal() - other.ordinal();
            }
        }
    }
}
