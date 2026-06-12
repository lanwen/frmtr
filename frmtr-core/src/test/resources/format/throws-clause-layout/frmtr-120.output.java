public abstract class Throws {

    void throwServiceException1() throws RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException2(String requestId) throws RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException3(String requestId, String accountId, String region) throws RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException4() throws RuntimeException, RuntimeException, RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException5(String requestId) throws RuntimeException, RuntimeException, RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException6(String requestId, String accountId, String region)
        throws RuntimeException, RuntimeException, RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException7(String requestId, String accountId, String region, String payload)
        throws RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException8(String requestId, String accountId, String region, String payload)
        throws RuntimeException, RuntimeException, RuntimeException {
        throw new RuntimeException();
    }

    void throwServiceException9(String requestId, String accountId, String region, String payload)
        throws RuntimeException, RuntimeException, RuntimeException, RuntimeException {
        throw new RuntimeException();
    }

    void aVeryLongNameForAMethodWhichShouldBreakTheThrowsClause()
        throws VeryLongException {}

    void aVeryLongNameForAMethodWhichShouldBreakTheThrowsClause()
        throws VeryLongException, VeryLongException {}

    void aVeryLongNameForAMethodWhichShouldBreakTheThrowsClause()
        throws ValidationException, TransportException, RetryableException, TimeoutException, AuthorizationException, ConflictException, AuditException {}

    abstract void abstractThrowServiceException1() throws RuntimeException;

    abstract void abstractThrowServiceException2(String requestId) throws RuntimeException;

    abstract void abstractThrowServiceException3(String requestId, String accountId, String region)
        throws RuntimeException;

    abstract void abstractThrowServiceException4() throws RuntimeException, RuntimeException, RuntimeException;

    abstract void abstractThrowServiceException5(String requestId)
        throws RuntimeException, RuntimeException, RuntimeException;

    abstract void abstractThrowServiceException6(String requestId, String accountId, String region)
        throws RuntimeException, RuntimeException, RuntimeException;

    abstract void abstractThrowServiceException7(String requestId, String accountId, String region, String payload)
        throws RuntimeException, RuntimeException, RuntimeException;

    abstract void abstractThrowServiceException8(String requestId, String accountId, String region, String payload)
        throws RuntimeException, RuntimeException, RuntimeException, RuntimeException;

    public Throws(String requestId) throws RuntimeException {
        System.out.println("Constructor with throws that should not wrap");
    }

    public Throws(String requestId, String accountId, String region) throws RuntimeException {
        System.out.println("Constructor with throws that should wrap");
    }

    public Throws(String requestId, String accountId, String region, String payload, String checksum)
        throws RuntimeException {
        System.out.println("Constructor with throws that should wrap");
    }

    public Throws(String requestId, String accountId, String region, String payload, String checksum)
        throws RuntimeException, RuntimeException, RuntimeException, RuntimeException {
        System.out.println("Constructor with throws that should wrap");
    }
}
