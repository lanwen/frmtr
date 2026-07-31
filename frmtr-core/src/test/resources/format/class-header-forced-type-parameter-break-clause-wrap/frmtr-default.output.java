package sample;

public class ShipmentRoutePlanner<
    OriginZone,
    DestinationZone,
    TransitCarrier
> implements
    RouteOptimizer<OriginZone, DestinationZone, TransitCarrier>,
    CapacityAwareScheduler,
    DeliveryWindowValidator {}
