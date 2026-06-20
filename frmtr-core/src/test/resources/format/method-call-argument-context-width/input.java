class MethodCallArgumentContextWidth {
    void recordBranch(DocList docs, ShipmentBranch shipmentBranch, ShipmentRenderer shipmentRenderer) {
        docs.add(shipmentBranch.isFallbackRoute() ? shipmentRenderer.format(shipmentBranch) : nestedShipmentBranch(shipmentBranch));
    }

    Doc recordHeader(Doc preparedCommentDoc, String routeHeaderText, RouteLoop loopStatement) {
        return Doc.concat(preparedCommentDoc, Doc.HARD_LINE, Doc.text(routeHeaderText + ";" + trailingEmptyRouteComment(loopStatement)));
    }

    void recordGroupedBranch() {
        Doc doc = Doc.group(Doc.concat(
            Doc.text("prefix"),
            Doc.ifBreak(Doc.text("-broken-branch"), Doc.text("-flat"))
        ));
        sink(doc);
    }

    String diagnosticLabel(SourceRegion region, String kind) {
        return "%s:%s@%d:%d-%d:%d"
                .formatted(
                    "sample",
                    kind,
                    region.beginLine(),
                    region.beginColumn(),
                    region.endLine(),
                    region.endColumn()
                );
    }
}
