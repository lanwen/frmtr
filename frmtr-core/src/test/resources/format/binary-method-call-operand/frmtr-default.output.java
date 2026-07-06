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
                .anyMatch(
                    item -> currentIndentedWidth.applyAsInt(
                        declarationPrefix + item.getNameAsString()
                    ) > options.lineWidth()
                );
    }

    boolean arrayRouteOverflows(RoutePair pair, Options options) {
        if (
            pair.value() instanceof ArrayValue arrayValue
            && currentIndentedWidth.applyAsInt(
                pair.name()
                    + " = "
                    + compactArrayValue(arrayValue)
            ) > options.lineWidth()
        ) {
            return true;
        }
        return false;
    }

    boolean arrayRouteOverflowsWithAnotherAnd(RoutePair pair, Options options) {
        if (
            pair.value() instanceof ArrayValue arrayValue
            && currentIndentedWidth.applyAsInt(
                pair.name()
                    + " = "
                    + compactArrayValue(arrayValue)
               ) > options.lineWidth()
            && pair.name().length() > 0
        ) {
            return true;
        }
        return false;
    }

    String selectorPrefix(RouteAssemblyStep routeAssemblyStep, RouteTextFormatter routeTextFormatter) {
        return routeAssemblyStep.context().map(routeContext -> routeTextFormatter.compact(routeContext) + ".").orElse("")
            + routeAssemblyStep.templateTypeArguments()
                    .map(typeArguments -> "<" + routeTextFormatter.compactQualifiedTypes(typeArguments) + ">")
                    .resolveRouteSelector()
            + (routeAssemblyStep.localRoute() ? "local" : "remote");
    }

    String routeImport(RouteDeclaration declaration) {
        return (declaration.externalRoute() ? "external " : "")
            + declaration.qualifiedName()
            + (declaration.allSegments() ? ".*" : "");
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

    boolean argumentStartsAfterType(RouteArgument argument, RouteExpression expression) {
        return argument.getRange()
                .map(
                    argumentRange -> argumentRange.begin.line
                            > expression.getType()
                                    .getRange()
                                    .map(typeRange -> typeRange.end.line)
                                    .orElse(argumentRange.begin.line)
                )
                .orElse(false);
    }

    boolean routeHeaderOverflows(RouteDeclaration declaration, RouteHeader header, Options options) {
        return routeHeaderWidth(
            declaration,
            header.flatText() + " " + (declaration.emptyStops() ? "{}" : "{")
        ) > options.lineWidth();
    }

    String selectorSegment(RouteCall scopeCall, RouteCall methodCall) {
        return "." + selector.apply(scopeCall) + "(" + compactJoin.apply(
            scopeCall.getArguments()
        ) + ")" + "." + selector.apply(methodCall) + "()";
    }
}
