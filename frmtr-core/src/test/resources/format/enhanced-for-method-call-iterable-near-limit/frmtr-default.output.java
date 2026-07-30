package dev.example;

class InventorySync {

    void reconcileNearLimit(WarehouseClient client) {
        for (ShipmentRecord shipment : client.pendingShipmentsForRegion(WarehouseFilter.of("dock-72ab", "east-hub"))) {
            process(shipment);
        }
    }

    void reconcileOverLimit(WarehouseClient client) {
        for (ShipmentRecord shipment : client.pendingShipmentsForRegion(
            WarehouseFilter.of("dock-72abc", "east-hub")
        )) {
            process(shipment);
        }
    }
}
