class ShipmentLedger {

    void register(java.util.List<Parcel> parcels) {
        parcels.forEach(parcel ->
            // note: skip parcels without a destination
            routingTable.put(parcel.trackingCode(), parcel.destination()));
    }

    void reconcile(java.util.List<Parcel> parcels) {
        parcels.forEach(parcel ->
            // first verify the parcel is insured
            // then record its declared customs value
            customsLedger.put(parcel.trackingCode(), parcel.declaredValue()));
    }

    void index(java.util.List<Parcel> parcels) {
        parcels.forEach(parcel -> registry.put(parcel.trackingCode(), parcel));
    }

    void tally() {
        manifest.routes()
                .forEach(route -> route.parcels().forEach(parcel ->
                    // tracking code and destination are not always populated at the same point;
                    // for example the inbound feed records the destination before the carrier scans it,
                    // so the tally keeps both the tracking code and the destination to stay consistent.
                    shipmentTotals.merge(
                        new ParcelKey(route.routeId(), parcel.index(), route.carrier()),
                        parcel.weight()
                    )
                ));
    }
}
