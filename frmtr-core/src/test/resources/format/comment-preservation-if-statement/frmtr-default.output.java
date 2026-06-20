class IfStatements {

    void commentsIfLineComment() {
        if (
            // test
            isEnabled
        ) {
        }

        if (
            isEnabled // test
        ) {
        }

        if (isEnabled) {
        } // test

        if (
            // test
            isEnabled
        ) {
        }

        if (
            true // comment
        ) {
            System.out.println("enabled");
        }
    }

    void commentsBetweenLogicalOperands(String keyType) {
        if (
            // legacy key envelope before registry draft 04
            // https://docs.example.invalid/token-envelope-03.html
            keyType.endsWith("-cert-v01@example.test")
            // current key envelope since registry draft 04
            // https://docs.example.invalid/token-envelope-04.html
            || keyType.endsWith("-cert")
        ) {
            System.out.println("enabled");
        }
    }

    void commentInsideGroupedOperand(RouteGate routeGate) {
        if (
            routeGate.hasPrimaryPath()
            && (
                // keep this note inside the grouped fallback operand
                routeGate.hasFallbackPath()
            )
        ) {
            System.out.println("fallback");
        }
    }

    void commentInsideNonLogicalCondition(RoutePlan routePlan) {
        if (
            // keep manual routing while backfill catches up
            routePlan.hasManualOverride()
        ) {
            System.out.println("manual");
        }
    }

    void commentsIfBlockComment() {
        if (/* test */ isEnabled) {
        }

        if (isEnabled) /* test */ {
        }

        if (isEnabled) /* test */ {
        }

        if (/* test */ isEnabled) {
        }
    }

    void commentsElseLineComment() {
        if (isEnabled) {
        }
        // test
        else {
        }

        if (isEnabled) {
        } else {
        } // test
    }

    void commentsElseBlockComment() {
        if (isEnabled) {
        } /* test */ else {
        }

        if (isEnabled) {
        } else /* test */ {
        }
    }
}
