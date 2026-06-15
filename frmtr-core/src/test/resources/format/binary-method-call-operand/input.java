class BinaryMethodCallOperandSample {
    String encodedName(String serial) {
        return (
            "group-" +
            EncoderFactory.getUrlEncoder()
                .withoutPadding()
                .encodeToString(serial.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    boolean any(Items<Item> items, String declarationPrefix, Options options) {
        return items
            .stream()
            .anyMatch(item -> currentIndentedWidth.applyAsInt(
                declarationPrefix + item.getNameAsString()
            ) > options.lineWidth());
    }

    String selectorPrefix(RouteAssemblyStep routeAssemblyStep, RouteTextFormatter routeTextFormatter) {
        return routeAssemblyStep.context().map(routeContext -> routeTextFormatter.compact(routeContext) + ".").orElse("")
            + routeAssemblyStep.templateTypeArguments().map(typeArguments -> "<" + routeTextFormatter.compactQualifiedTypes(typeArguments) + ">").resolveRouteSelector()
            + (routeAssemblyStep.localRoute() ? "local" : "remote");
    }

    String routeImport(RouteDeclaration declaration) {
        return (
            (declaration.externalRoute() ? "external " : "")
            + declaration.qualifiedName()
            + (declaration.allSegments() ? ".*" : "")
        );
    }

    boolean routeBudgetFits(RoutePlan routePlan, SegmentBudget segmentBudget, Options options) {
        return routePlan.hasOrigin()
            && routePlan.hasDestination()
            && segmentBudget.continuationRouteWidth(
                ") "
                    + routePlan.selectedOperator().displayText()
                    + " "
                    + segmentBudget.remainingSegmentExpression()
            ) <= options.lineWidth();
    }

    boolean routeHeaderOverflows(RouteDeclaration declaration, RouteHeader header, Options options) {
        return (
            routeHeaderWidth(declaration, header.flatText() + " " + (declaration.emptyStops() ? "{}" : "{"))
            > options.lineWidth()
        );
    }
}
