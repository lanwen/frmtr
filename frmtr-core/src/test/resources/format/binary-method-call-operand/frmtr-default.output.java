class BinaryMethodCallOperandSample {

    String encodedName(String serial) {
        return (
            "group-"
            + EncoderFactory.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(serial.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    boolean any(Items<Item> items, String declarationPrefix, Options options) {
        return items
                .stream()
                .anyMatch(item -> currentIndentedWidth.applyAsInt(
                        declarationPrefix + item.getNameAsString()
                    ) > options.lineWidth()
                );
    }

    String selectorPrefix(RouteAssemblyStep routeAssemblyStep, RouteTextFormatter routeTextFormatter) {
        return (
            routeAssemblyStep.context().map(routeContext -> routeTextFormatter.compact(routeContext) + ".").orElse("")
            + routeAssemblyStep
                    .templateTypeArguments()
                    .map(typeArguments -> "<" + routeTextFormatter.compactQualifiedTypes(typeArguments) + ">")
                    .resolveRouteSelector()
            + (routeAssemblyStep.localRoute() ? "local" : "remote")
        );
    }
}
