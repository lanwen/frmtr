class ShipmentLaneRouter {

    void assignLanes(RoutingEngine routingEngine, ZonePair zonePair, ShipmentBatch shipmentBatch) {
        selectedLane = routingEngine.laneAvailable(zonePair)
            ? routingEngine.primaryLaneFor(zonePair, shipmentBatch)
            : routingEngine.fallbackLaneFor(zonePair);
        this.selectedLaneForTheCurrentShipmentBatchAcrossZoneBoundaries =
            routingEngineConfigurationSnapshot.laneAvailabilityForThisZonePair
                ? routingEngine.primaryLaneFor(zonePair)
                : routingEngine.fallbackLaneFor(zonePair);
        shortLane = ready ? primary : fallback;
    }
}
