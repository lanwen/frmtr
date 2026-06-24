class ShipmentLedger {

    void register(java.util.List<Parcel> parcels) {
        parcels.forEach(
            parcel ->
                // note: skip parcels without a destination
                routingTable.put(parcel.trackingCode(), parcel.destination())
        );
    }

    void reconcile(java.util.List<Parcel> parcels) {
        parcels.forEach(
            parcel ->
                // first verify the parcel is insured
                // then record its declared customs value
                customsLedger.put(parcel.trackingCode(), parcel.declaredValue())
        );
    }

    void index(java.util.List<Parcel> parcels) {
        parcels.forEach(parcel -> registry.put(parcel.trackingCode(), parcel));
    }
}
