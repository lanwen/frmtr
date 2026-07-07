class ParameterListWidthReflow {

    // Written multiline in the source but fits on one line: reprint-by-default reflows it, since parameter
    // layout is now width-driven and no longer preserves the author's line breaks.
    OrderReceipt placeOrder(Customer customer, ShoppingCart shoppingCart) {
        return fulfillmentService.submit(customer, shoppingCart);
    }

    // Over the line width even flattened: still breaks by width, one parameter per continuation line.
    ShipmentPlan planShipment(
            WarehouseCatalog warehouseCatalog,
            DeliveryWindow requestedDeliveryWindow,
            CarrierRoutingTable carrierRoutingTable,
            PackagingConstraints packagingConstraints
    ) {
        return logisticsPlanner.plan(
            warehouseCatalog,
            requestedDeliveryWindow,
            carrierRoutingTable,
            packagingConstraints
        );
    }

    // Already on one line and fits: unchanged.
    Invoice issueInvoice(Order order, TaxProfile taxProfile) {
        return billing.issue(order, taxProfile);
    }

    // throws sticks to ")": signature with throws fits, so the whole thing stays on one line even though the
    // author wrote throws on its own line.
    Report buildReport(ReportContext reportContext) throws ReportException {
        return reportBuilder.build(reportContext);
    }

    // throws sticks to ")": parameters alone fit, but the signature plus throws is over width, so the args
    // expand first and throws follows the ")" rather than breaking onto its own line.
    Snapshot captureSnapshot(
            RegionSelector regionSelector, RetentionPolicy retentionPolicy
    ) throws SnapshotStoreException {
        return snapshotService.capture(regionSelector, retentionPolicy);
    }
}
