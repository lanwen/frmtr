public class MethodParameterLists {

    public static void main(String[] commandArgs) {}

    public void none() {}

    public void one(String accountId) {}

    public void three(String accountId, Integer retryCount, String region) {}

    public void longListOfParametersThatShouldBreak(
            String accountId,
            Integer retryCount,
            String region,
            Integer batchSize,
            String owner,
            Integer shardCount
    ) {}

    private List<RouteGapPlanner.RawGapRegion> recoverableRouteGapRegions(
            RouteModule declaration,
            RecoveredRoutePlanner.Plan<RouteDirective> plan
    ) {
        return routeGaps.rawGapRegions(plan);
    }

    void lastParameterDotDotDot(String message, String... labels) {}

    void variableArityParameters(Object @Nullable... errorMessageArgs) {}

    void variableArityParameters(Object[] @Nullable... errorMessageArgs) {}

    void variableArityParameters(byte[] @Nullable... errorMessageArgs) {}

    void variableArityParameters(byte @Nullable... errorMessageArgs) {}

    void variableArityParameters(final String... names) {}

    void variableArityParameters(byte... payloadBytes) {}

    void variableArityParameters(final String[]... nameGroups) {}

    void variableArityParameters(byte[]... payloadChunks) {}

    public static void renderMessage(
      boolean enabled,
      /*
      * TODO(catalog): Remove nullable templates after generated plans
      * use the shared message fallback instead of absent values.
      */
      @Nullable String messageTemplate,
      @Nullable Object @Nullable... messageArguments
    ) {}
}
