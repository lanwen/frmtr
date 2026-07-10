class ObjectRootReturnChainRankingSample {

    HttpResponse compactRootBreaksFinalArguments(RequestContext requestContext) {
        return new HttpResponseBuilder(configuredRegionCode).sealWithSignature(
            incomingRequestContext,
            strictModeIsEnabled
        );
    }

    ConnectionPool fanOutWhenSelectorOpenerOverflows(DataSourceConfiguration dataSourceConfiguration) {
        return new PooledConnectionFactoryBuilder(configuredPrimaryReplicaDataSourceHandle)
            .establishValidatedConnectionPool(retryPolicyName, healthProbeInterval);
    }
}
