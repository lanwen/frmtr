class ParameterListWidthReflow {

    // Written multiline in the source but fits on one line: reprint-by-default reflows it, since parameter
    // layout is now width-driven and no longer preserves the author's line breaks.
    OrderReceipt placeOrder(
            Customer customer,
            ShoppingCart shoppingCart
    ) {
        return fulfillmentService.submit(customer, shoppingCart);
    }

    // Over the line width even flattened: still breaks by width, one parameter per continuation line.
    ShipmentPlan planShipment(WarehouseCatalog warehouseCatalog, DeliveryWindow requestedDeliveryWindow, CarrierRoutingTable carrierRoutingTable, PackagingConstraints packagingConstraints) {
        return logisticsPlanner.plan(warehouseCatalog, requestedDeliveryWindow, carrierRoutingTable, packagingConstraints);
    }

    // Already on one line and fits: unchanged.
    Invoice issueInvoice(Order order, TaxProfile taxProfile) {
        return billing.issue(order, taxProfile);
    }
}
