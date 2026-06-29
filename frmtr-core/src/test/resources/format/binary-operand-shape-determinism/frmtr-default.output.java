class BinaryOperandShapeDeterminism {

    // Flat source: every && operand's call arguments are already on one line.
    boolean matchesFlatSource(CatalogEntry other) {
        if (this == other) {
            return true;
        }
        return Objects.equals(serialId, other.serialId)
            && Arrays.equals(payloadBytes, other.payloadBytes)
            && Objects.equals(displayLabel, other.displayLabel)
            && Objects.equals(headerKeys, other.headerKeys)
            && Objects.equals(headerValues, other.headerValues);
    }

    // Pre-broken source: the same chain, but the author wrapped two operands' argument
    // lists across lines. The operand break must not follow that incidental shape, so this
    // formats byte-identically to matchesFlatSource above.
    boolean matchesBrokenSource(CatalogEntry other) {
        if (this == other) {
            return true;
        }
        return Objects.equals(serialId, other.serialId)
            && Arrays.equals(payloadBytes, other.payloadBytes)
            && Objects.equals(displayLabel, other.displayLabel)
            && Objects.equals(headerKeys, other.headerKeys)
            && Objects.equals(headerValues, other.headerValues);
    }

    // Flat source: a leading-operator || chain whose operands fit, so each stays flat.
    boolean reachableFlatSource(RouteHandle handle, EndpointRef endpoint) {
        return handle.isOpen(endpoint.descriptorName())
            || handle.isPending(endpoint.descriptorName(), endpoint.retryBudget())
            || handle.isQueued(endpoint.descriptorName());
    }

    // Pre-broken source: same chain, middle operand's arguments wrapped by the author.
    boolean reachableBrokenSource(RouteHandle handle, EndpointRef endpoint) {
        return handle.isOpen(endpoint.descriptorName())
            || handle.isPending(endpoint.descriptorName(), endpoint.retryBudget())
            || handle.isQueued(endpoint.descriptorName());
    }

    // Width still drives breaking: this operand genuinely overflows 120 columns, so it
    // must explode its argument list regardless of whether the source had it flat or broken.
    boolean computeWideFlatSource(MeasurementContext measurementContext, ColumnBudget columnBudget) {
        return measurementContext.isCalibrated()
            && columnBudget.continuationMeasurementWidthForLongLabelledExpressionInputs(
                measurementContext.primaryLabelExpression(),
                measurementContext.secondaryLabelExpression()
            ) > columnBudget.maximumWidth();
    }

    // Same genuinely-overflowing operand with broken source: identical exploded output.
    boolean computeWideBrokenSource(MeasurementContext measurementContext, ColumnBudget columnBudget) {
        return measurementContext.isCalibrated()
            && columnBudget.continuationMeasurementWidthForLongLabelledExpressionInputs(
                measurementContext.primaryLabelExpression(),
                measurementContext.secondaryLabelExpression()
            ) > columnBudget.maximumWidth();
    }
}
