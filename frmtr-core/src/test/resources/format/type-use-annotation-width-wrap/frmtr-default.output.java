class ShipmentValidationSchema {

    Map<@Size(max = 40) String, ShipmentManifest> manifestsByCompactTrackingCode;

    Map<
        @Size(
            min = 8,
            max = 40,
            message = "Carrier tracking code must follow the negotiated per-region label layout"
        ) String,
        ShipmentManifest
    > manifestsByValidatedTrackingCode;

    List<
        @Deprecated @Size(
            min = 1,
            max = 24,
            message = "Region codes stay within the negotiated dispatch boundary"
        ) String
    > retiredRegionCodes;
}
