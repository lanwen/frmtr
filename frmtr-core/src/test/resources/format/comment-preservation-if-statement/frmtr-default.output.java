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
            // legacy key type format before registry draft 04
            keyType.endsWith("-cert-v01@openssh.com")
            // current key type format since registry draft 04
            || keyType.endsWith("-cert")
        ) {
            System.out.println("enabled");
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
