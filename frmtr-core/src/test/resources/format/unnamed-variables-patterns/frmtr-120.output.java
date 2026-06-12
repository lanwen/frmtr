class UnnamedPatternExamples {

    static int count(Iterable<Order> orders) {
        int total = 0;
        for (Order _ : orders) // Unnamed variable
            total++;
        return total;
    }

    void simpleForLoop() {
        for (int index = 0, _ = sideEffect(); index < 10; index++) {}
    }

    void assignment() {
        while (queue.size() >= 3) {
            var firstItem = queue.remove();
            var secondItem = queue.remove();
            var _ = queue.remove(); // Unnamed variable
        }
    }

    void multipleAssignment() {
        while (queue.size() >= 3) {
            var firstItem = queue.remove();
            var _ = queue.remove(); // Unnamed variable
            var _ = queue.remove(); // Unnamed variable
        }
    }

    void catchClause() {
        try {
            int parsedNumber = Integer.parseInt(inputText);
        } catch (NumberFormatException _) {
            // Unnamed variable
            System.out.println("Bad number: " + inputText);
        }
    }

    void multipleCatchClauses() {
        try {
        } catch (Exception _) {
        } catch (Throwable _) {
            // Unnamed variable
        } // Unnamed variable
    }

    void tryWithResources() {
        try (var _ = ScopedContext.acquire()) {
            // Unnamed variable
        }
    }

    void lambda() {
        stream.collect(Collectors.toMap(String::toUpperCase, _ -> "NO_DATA")); // Unnamed variable
    }

    void switchTypePattern() {
        switch (ball) {
            case RedBall _ -> process(ball); // Unnamed pattern variable
            case BlueBall _ -> process(ball); // Unnamed pattern variable
            case GreenBall _ -> stopProcessing(); // Unnamed pattern variable
        }
    }

    void switchRecordPattern() {
        switch (box) {
            case Box(RedBall _) -> processBox(box); // Unnamed pattern variable
            case Box(BlueBall _) -> processBox(box); // Unnamed pattern variable
            case Box(GreenBall _) -> stopProcessing(); // Unnamed pattern variable
            case Box(var _) -> pickAnotherBox(); // Unnamed pattern variable
        }
    }

    void multipleSwitchPatterns() {
        switch (box) {
            case Box(RedBall _) -> processBox(box);
            case Box(BlueBall _) -> processBox(box);
            case Box(GreenBall _) -> stopProcessing();
            case Box(var _) -> pickAnotherBox();
        }
    }

    void multipleSwitchPatternsWithGuard() {
        switch (box) {
            case Box(RedBall _) when priority == 42 -> processBox(box);
            case Box(BlueBall _) when priority == 42 -> processBox(box);
        }
    }

    void instanceofExpressions() {
        if (shape instanceof ColoredPoint(Point(int pointX, int pointY), _)) {
        }
        if (shape instanceof ColoredPoint(_, Color shade)) {
        }
        if (shape instanceof ColoredPoint(Point(int pointX, _), _)) {
        }
    }

    void switchLabelWithMatchAllPattern() {
        switch (box) {
            case Box(RedBall _) -> processBox(box);
            case Box(BlueBall _) -> processBox(box);
            case Box(GreenBall _) -> stopProcessing();
            case Box(_) -> pickAnotherBox();
        }
    }

    int wrappingMultipleSwitchPatterns() {
        return switch ("") {
            case CustomerLedgerEntry ledgerEntry -> 0;
            case CustomerLedgerEntry ledgerEntry -> 0;
            case CustomerLedgerEntry ledgerEntry -> 0;
            case AuditPair(PrimaryAudit primaryAudit) -> 0;
            case AuditPair(SecondaryAudit secondaryAudit) -> 0;
            case AuditPair(PrimaryAudit primaryAudit) when true -> 0;
            case AuditPair(SecondaryAudit secondaryAudit) when true -> 0;
            case AuditPair(CustomerLedgerEntry leftLedgerEntry, CustomerLedgerEntry rightLedgerEntry) -> 0;
            case AuditPair(CustomerLedgerEntry leftLedgerEntry, CustomerLedgerEntry rightLedgerEntry) when (
                this.referenceLedgerEntry > leftLedgerEntry && this.referenceLedgerEntry > rightLedgerEntry
            ) -> compareLedgerEntries(leftLedgerEntry, rightLedgerEntry, leftLedgerEntry, rightLedgerEntry);
        };
    }
}
