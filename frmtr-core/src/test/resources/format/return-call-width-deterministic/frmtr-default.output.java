package sample;

class ServiceClientFactory {

    ConnectionPool fitsButWasWrappedInSource(DataSourceConfig config) {
        return new ConnectionPoolBuilder().connectionString(reportingReplicaJdbcConnectionString);
    }

    PaymentRequest overflowingChainStillBreaks(PaymentContext context) {
        return new PaymentRequestBuilder()
                .amount(totalAmountInCents)
                .currency(billingCurrencyCode)
                .recipient(payeeAccountId)
                .build();
    }
}
