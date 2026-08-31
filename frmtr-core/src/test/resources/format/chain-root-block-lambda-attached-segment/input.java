class HealthCheckAuthorizationTest {

    void rejectsUnauthenticatedRequest() {
        assertThatThrownBy(() -> {
            stub.check(HealthCheckRequest.newBuilder().build());
        }).satisfies(assertStatusCode(Status.Code.UNAUTHENTICATED));
    }

    void rejectsUnauthenticatedRequestWithDetailedStatusMismatch() {
        assertThatThrownBy(() -> {
            stub.check(HealthCheckRequest.newBuilder().build());
        }).satisfies(assertStatusCodeMatchesExpectedGrpcErrorCodeForUnauthenticatedRequests(Status.Code.UNAUTHENTICATED));
    }

    void rejectsUnauthenticatedRequestAfterBuildingIt() {
        assertThatThrownBy(() -> {
            HealthCheckRequest request = HealthCheckRequest.newBuilder().build();
            stub.check(request);
        }).satisfies(assertStatusCode(Status.Code.UNAUTHENTICATED));
    }
}
