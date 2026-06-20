class LambdaBinaryBodyOpenerSample {

    void callArg(Planner routePlanner, Catalog deliveryCatalog, Request allocationRequest) {
        routePlanner.collectEligibleDeliveryOptions(deliveryCatalog, allocationRequest, rule -> rule.matches()
                && rule.requires(allocationRequest)
                && rule.accepts(routePlanner)
        );
    }

    boolean nested(RoutePlan plan, InventorySnapshot inventory, PricingMatrix pricing) {
        return plan
                .candidateRoutes()
                .flatMap(
                    route -> inventory.availableWindows().flatMap(
                        window -> pricing.discountRules().map(
                            rule -> route.regionCode() == window.regionCode()
                                    && route.capacityUnits() > window.reservedUnits()
                                    && route.capacityUnits() <= window.reservedUnits() + 2
                                    && route.regionCode() == rule.regionCode()
                                    && route.capacityUnits() < rule.maximumUnits()
                                    && rule.minimumUnits() > route.minimumUnits()
                                    && window.slotCode() == rule.slotCode()
                                    && rule.maximumUnits() < route.maximumUnits()
                        )
                    )
                )
                .orElse(false);
    }

    void negatedParenthesizedBody(Items<Item> items) {
        items.anyMatch(item -> !(
            item.isNameExpr()
                || item.isFieldAccessExpr()
                || item.isThisExpr()
                || item.isSuperExpr()
                || item.isLiteralExpr()
        ));
    }
}
