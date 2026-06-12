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
