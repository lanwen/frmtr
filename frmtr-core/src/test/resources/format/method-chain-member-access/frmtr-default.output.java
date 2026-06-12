public class MethodChainSamples {

    public void doSomething() {
        return new RequestBuilder().something().more();
    }

    public void doSomethingNewWithComment() {
        new RequestBuilder()
            // comment
            .something()
            .more();

        new RequestBuilder()
            .something()
            // comment
            .more();
    }

    public void doSomethingWithComment() {
        Object
            // comment
            .something()
            .more();

        java.Object
            // comment
            .something()
            .more();

        java.enterpriseCustomerProvisioningServiceNamespace.Object
            // comment
            .something()
            .more();

        java.enterpriseCustomerProvisioningServiceNamespace.Object.something().more();

        java.enterpriseCustomerProvisioningServiceNamespace.Object.something().more();

        java.enterpriseCustomerProvisioningServiceNamespace.Object.something().more();

        Object.something()
            // comment
            .more();

        enterpriseCustomerProvisioningServiceNamespace.java
            // comment
            .util()
            .java.java();

        enterpriseCustomerProvisioningServiceNamespace.java.util().java.java();

        enterpriseCustomerProvisioningServiceNamespace.java
            /* comment */
            .util()
            .java.java();

        enterpriseCustomerProvisioningServiceNamespace.java
            /* comment */
            .util()
            .java.java();

        enterpriseCustomerProvisioningServiceNamespace.java
            /* comment */ .util()
            .java.java();
    }

    public void doSomethingWithComment() {
        requestBuilder
            // comment
            .something()
            .more();

        requestBuilder
            .something()
            // comment
            .more();
    }

    public void doSomethingNewWithComment() {
        return new RequestBuilder()
            /* comment */
            .something()
            .more();
    }

    public void doSomethingWithComment() {
        return RequestBuilder
            /* comment */
            .something()
            .more();
    }

    public void doSomethingWithComment() {
        return requestBuilder
            /* comment */
            .something()
            .more();
    }

    public void doSomethingLongNew() {
        return something().more().and().that().as().well().but().not().something().something();
    }

    public void doSomethingLongWithArgument() {
        return something()
            .more(firstArgument, secondArgument)
            .and(firstArgument, secondArgument, thirdArgument, fourthArgument, fifthArgument);
    }

    public void doSomethingLongNew2() {
        return new RequestBuilder().something().more().and().that().as().well().but().not().something();
    }

    public void doSomethingLongStatic() {
        return RequestBuilder.something().more().and().that().as().well().but().not().something();
    }

    public void singleInvocationOnNewExpression() {
        new SessionInvocation(requestIdentifierValue, requestIdentifierValue).invocation(
            requestIdentifierValue,
            requestIdentifierValue
        );
    }

    public void multipleInvocationsOnNewExpression() {
        new SessionInvocation(requestIdentifierValue, requestIdentifierValue)
            .invocation(requestIdentifierValue, requestIdentifierValue)
            .andAnother();
    }

    void methodReferences() {
        userRecords.stream().map(UserRecord::toString).forEach(auditLog::info);
    }
}
