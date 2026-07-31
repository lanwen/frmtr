package demo;

class ConnectionSessionCoordinatorForRetryWindowAndFailover {
    void processIncomingConnectionRequestFromRemoteGatewayLink(ConnectionRequest request, ConnectionContext context) {
        request.accept(context);
    }

    void processIncomingConnectionRequestFromRemoteGatewayLinkNow(ConnectionRequest request, ConnectionContext context) {
        request.accept(context);
    }

    ConnectionSessionCoordinatorForRetryWindowAndFailover(
        ConnectionRequest request,
        ConnectionContext context
    ) throws ConnectionSetupException {
        this.request = request;
    }

    ConnectionSessionCoordinatorForRetryWindowAndFailover(
        ConnectionRequest primaryRequest,
        ConnectionContext primaryContext,
        RetryBudget retryBudget,
        FailoverPolicy failoverPolicy
    ) throws ConnectionSetupException {
        this.primaryRequest = primaryRequest;
    }

    class RetrySessionAttempt {
        RetrySessionAttempt(ConnectionRequestForRetrySession request, ConnectionContextForRetrySession context) throws ConnectionSetupException {
            this.request = request;
        }
    }
}
