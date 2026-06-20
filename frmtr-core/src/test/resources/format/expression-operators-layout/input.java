public class ExpressionOperatorSamples {

    public void equals(int i) {
        if (i == 1) {
            System.out.println("i equals 1");
        }
    }

    public void unequals(int i) {
        if (i != 1) {
            System.out.println("i not equals 1");
        }
    }

    public void equalsComplex(String text) {
        if (text.equals("String")) {
            System.out.println("string equals String");
        }
    }

    public void greater(int i) {
        if (i > 1) {
            System.out.println("i greater 1");
        }
    }

    public void less(int i) {
        if (i < 1) {
            System.out.println("i less 1");
        }
    }

    public void greaterEquals(int i) {
        if (i >= 1) {
            System.out.println("i greater/equals 1");
        }
    }

    public void lessEquals(int i) {
        if (i <= 1) {
            System.out.println("i less/equals 1");
        }
    }

    public void and() {
        if (true && true) {
            System.out.println("and");
        }
    }

    public void or() {
        if (true || false) {
            System.out.println("or");
        }
    }

    public void not() {
        if (!false) {
            System.out.println("not");
        }
    }

    public void parenthesized() {
        if (true && (false || true)) {
            System.out.println("parenthesized");
        }
    }

    public void instanceOf() {
        if (candidate instanceof Object) {
            System.out.println("instanceOf");
        }
    }

    public void printSimple() {
        if (statusCode == 42) {
        }

        if (statusCode != 42) {
            System.out.println("Why not 42 !");
        }
    }

    public void printIf() {
        Object activePrincipal =
            new SessionContext().getSingleton().getAuthentication().getCredentials().getRights().getName();

        if (
            statusCode == 42 ||
            (statusCode == 42 && statusCode == 42 && statusCode == 42) ||
            (statusCode == 42 && statusCode == 42)
        ) {
        }

        if (statusCode != 42 && 42/42 || statusCode & 42 && statusCode > 42 || statusCode < 42 && statusCode == 42) {
        }

        if (statusCode != 42 && statusCode == 42) {
        }
    }

    public void printSwitch() {
        switch (
            statusCode == 42 ||
            (statusCode == 42 && statusCode == 42 && statusCode == 42) ||
            (statusCode == 42 && statusCode == 42)
        ) {
        }

        switch (statusCode != 42 && 42/42 || statusCode & 42 && statusCode > 42 || statusCode < 42 && statusCode == 42) {
        }

        switch (statusCode != 42) {
        }

        switch (statusCode != 42 && statusCode == 42) {
        }
    }

    public void printWhile() {
        while (true) throw new RuntimeException();

        while (
            statusCode == 42 ||
            (statusCode == 42 && statusCode == 42 && statusCode == 42) ||
            (statusCode == 42 && statusCode == 42)
        ) {}

        while (statusCode != 42 && 42/42 || statusCode & 42 && statusCode > 42 || statusCode < 42 && statusCode == 42) {}

        while (statusCode != 42) {}

        while (statusCode != 42 && statusCode == 42) {}
    }

    public void printDoWhile() {
        do {
            System.out.println("Formatter input is ready!");
        } while (
            statusCode == 42 ||
            (statusCode == 42 && statusCode == 42 && statusCode == 42) ||
            (statusCode == 42 && statusCode == 42)
        );

        do {
            System.out.println("Formatter input is ready!");
        } while (statusCode != 42 && 42/42 || statusCode & 42 && statusCode > 42 || statusCode < 42 && statusCode == 42);

        do {
            System.out.println("Formatter input is ready!");
        } while (statusCode != 42);

        do {
            System.out.println("Formatter input is ready!");
        } while (statusCode != 42 && statusCode == 42);
    }

    public void printSynchronized() {
        synchronized (
            statusCode == 42 ||
            (statusCode == 42 && statusCode == 42 && statusCode == 42) ||
            (statusCode == 42 && statusCode == 42)
        ) {
            System.out.println("Formatter input is ready!");
        }

        synchronized (statusCode != 42 && 42/42 || statusCode & 42 && statusCode > 42 || statusCode < 42 && statusCode == 42) {
            System.out.println("Formatter input is ready!");
        }

        synchronized (statusCode == 42) {
            System.out.println("Formatter input is ready!");
        }

        synchronized (statusCode != 42 && statusCode == 42) {
            System.out.println("Formatter input is ready!");
        }
    }

    public void printNonLogicalControlConditions() {
        switch (routePolicy.accepts(routeContext.primaryStop(), routeContext.backupStop(), segmentPlan.candidateWindow())) {
        }

        while (routePolicy.accepts(routeContext.primaryStop(), routeContext.backupStop(), segmentPlan.candidateWindow())) {}

        synchronized (routePolicy.accepts(routeContext.primaryStop(), routeContext.backupStop(), segmentPlan.candidateWindow())) {
        }
    }

    public void longFullyQualifiedName() {
        com.me.very.very.very.very.very.very.very.very.very.very.very.very.very.longg.fully.qualified.name.FullyQualifiedName.builder().build();

        com.FullyQualifiedName.builder();
    }

    public void unannTypePrimitiveWithMethodReferenceSuffix(String[] args) {
        List.of(
            new double[][] { 1, 2, 3, 4.1, 5.6846465 },
            new double[][] { 1, 2, 3, 4.1, 5.6846465 },
            new double[][] { 1, 2, 3, 4.1, 5.6846465 }
        ).toArray(double[][]::new);
    }

    public void staticMethodInvocationWithSingleChainedMethodInvocation() {
        List.of(firstProjectionArgument, firstProjectionArgument).chained(
            firstProjectionArgument,
            firstProjectionArgument
        );
    }

    public void staticMethodInvocationWithMultipleChainedMethodInvocation() {
        List.of(firstProjectionArgument, firstProjectionArgument)
            .chained(firstProjectionArgument, firstProjectionArgument)
            .another();
    }

    public void nonStaticMultipleChainedMethodInvocations() {
        registry
            .of(firstProjectionArgument, firstProjectionArgument)
            .chained(firstProjectionArgument, firstProjectionArgument);
    }

    public void typeExpressionsInFqnParts() {
        var map = new <String, Integer>HashMap<String, Integer>(Map.of("A", 1));
    }

    void parenthesesWithLeadingAndTrailingBreak() {
        (primaryReady + secondaryReady + regionReady + acceptedResult + fallbackResult).resolve();
    (primaryReady + secondaryReady + regionReady + acceptedResult + fallbackResult)::resolve;

        primaryReady = (secondaryReady && regionReady ? acceptedResult : fallbackResult).resolve();
        primaryReady = (secondaryReady && regionReady ? acceptedResult : fallbackResult)::resolve;
        primaryReady = (secondaryReady && regionReady ? acceptedResult : fallbackResult)[resolve];

        ResolvedPlan primaryReady = (secondaryReady && regionReady ? acceptedResult : fallbackResult).resolve();
        ResolvedPlan primaryReady = (secondaryReady && regionReady ? acceptedResult : fallbackResult)::resolve;
        ResolvedPlan primaryReady = (secondaryReady && regionReady ? acceptedResult : fallbackResult)[resolve];

        switch (event) {
            case PendingEvent pendingEvent when (
                regionReady && acceptedResult && fallbackResult
            ) -> resolve;
        }

        return (primaryReady && secondaryReady && regionReady && acceptedResult && fallbackResult && resolve);
    }

    void parenthesesWithTrailingBreak() {
        (primaryReady && secondaryReady && regionReady ? acceptedResult : fallbackResult).resolve();
    (primaryReady && secondaryReady && regionReady ? acceptedResult : fallbackResult)::resolve;
        (primaryReady && secondaryReady && regionReady ? acceptedResult : fallbackResult)[resolve];
    }

    void parenthesesWithoutBreak() {
        (primaryReady -> secondaryReady && regionReady ? acceptedResult : fallbackResult).resolve();
    (primaryReady -> secondaryReady && regionReady ? acceptedResult : fallbackResult)::resolve;
        (primaryReady -> secondaryReady && regionReady ? acceptedResult : fallbackResult)[resolve];

        primaryReady = (secondaryReady -> regionReady ? acceptedResult : fallbackResult).resolve();
        primaryReady = (secondaryReady -> regionReady ? acceptedResult : fallbackResult)::resolve;
        primaryReady = (secondaryReady -> regionReady ? acceptedResult : fallbackResult)[resolve];

        ResolvedPlan primaryReady = (secondaryReady -> regionReady ? acceptedResult : fallbackResult).resolve();
        ResolvedPlan primaryReady = (secondaryReady -> regionReady ? acceptedResult : fallbackResult)::resolve;
        ResolvedPlan primaryReady = (secondaryReady -> regionReady ? acceptedResult : fallbackResult)[resolve];
    }

    void unaryExpression() {
        int a = +x;
        int b = -x;
        int c = ~x;
        boolean d = !x;
        int e = ~~x;
        int f = -+x;
    }
}
