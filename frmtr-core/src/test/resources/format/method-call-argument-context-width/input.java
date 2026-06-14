class MethodCallArgumentContextWidth {
    void recordBranch(DocList docs, ShipmentBranch shipmentBranch, ShipmentRenderer shipmentRenderer) {
        docs.add(shipmentBranch.isFallbackRoute() ? shipmentRenderer.format(shipmentBranch) : nestedShipmentBranch(shipmentBranch));
    }

    Doc recordHeader(Doc preparedCommentDoc, String routeHeaderText, RouteLoop loopStatement) {
        return Doc.concat(preparedCommentDoc, Doc.HARD_LINE, Doc.text(routeHeaderText + ";" + trailingEmptyRouteComment(loopStatement)));
    }
}
