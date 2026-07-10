class ConditionalExpression {

    int ternaryOperationThatShouldBreak() {
        int shortInteger = thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne
            ? thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne
            : thisIsAShortInteger;
        return thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne
            ? thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne
            : thisIsAShortInteger;
    }

    int ternaryOperationThatShouldBreak2() {
        int shortInteger = thisIsAVeryLongInteger
            ? thisIsAnotherVeryLongOne
            : thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne;
        return thisIsAVeryLongInteger
            ? thisIsAnotherVeryLongOne
            : thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne;
    }

    RoutingChoice ternaryOperationThatShouldBreak3() {
        var expressionResult =
            featureEnabled && quotaAvailable && regionAvailable && planReady && fallbackReady && policyReady
                ? primaryResult
                : backupResult;
        var result =
            featureEnabled && quotaAvailable && regionAvailable && planReady && fallbackReady && policyReady
                ? primaryResult
                : backupResult;
        result = featureEnabled && quotaAvailable && regionAvailable && planReady && fallbackReady && policyReady
            ? primaryResult
            : backupResult;
        select(
            featureEnabled && quotaAvailable && regionAvailable && planReady && fallbackReady && policyReady
                ? primaryResult
                : backupResult
        );
        return featureEnabled && quotaAvailable && regionAvailable && planReady && fallbackReady && policyReady
            ? primaryResult
            : backupResult;
    }

    int ternaryOperationThatShouldNotBreak() {
        int a = b ? b : c;
        return b ? b : c;
    }

    void nestedTernary() {
        var selectedPlan = featureEnabled
            ? quotaAvailable
            : regionAvailable
                ? planReady
                : fallbackReady
                    ? policyReady
                    : primaryResult;
    }

    void ternaryWithComments() {
        var first = a
            // b
            ? b
            // c
            : c;
        var second = a
            // b
            ? b
            // c
            : c;
        var third = a
            // b
            ? b
            // c
            : c;
        var fourth = a
            ? b // b
            : c; // c
    }

    void ternaryInParentheses() {
        select(featureEnabled ? quotaAvailable : regionAvailable.planReady().fallbackReady().policyReady());
    }

    void assignment() {
        RoutingChoice featureEnabled = quotaAvailable(regionAvailable, planReady, fallbackReady) != policyReady
            ? primaryResult
            : backupResult;

        RoutingChoice featureEnabled =
            quotaAvailable(expandedRegionAvailable, planReady, fallbackReady) != policyReady
                ? primaryResult
                : backupResult;

        RoutingChoice featureEnabled =
            quotaAvailable(expandedRegionAvailable, migrationPlanReady, fallbackReady) != policyReady
                ? primaryResult
                : backupResult;

        featureEnabled =
            quotaAvailable(regionAvailable, planReady, fallbackReady) != policyReady
                ? primaryResult
                : backupResult;

        featureEnabled =
            quotaAvailable(expandedRegionAvailable, planReady, fallbackReady) != policyReady
                ? primaryResult
                : backupResult;

        featureEnabled =
            quotaAvailable(expandedRegionAvailable, migrationPlanReady, fallbackReady) != policyReady
                ? primaryResult
                : backupResult;
    }
}
