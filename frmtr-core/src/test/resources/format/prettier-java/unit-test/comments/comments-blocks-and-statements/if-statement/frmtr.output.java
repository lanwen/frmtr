class IfStatements {

    void commentsIfLineComment() {
        // test
        if (t) {}

        if (t) {}

        if (t) {}

        if (t) {}

        if (true) {
            System.out.println("Oops");
        }
    }

    void commentsIfBlockComment() {
        if (t) {}

        if (t) /* test */
        {}

        if (t) /* test */
        {}

        if (t) {}
    }

    void commentsElseLineComment() {
        // test
        if (t) {} else // test
        {}

        if (t) {} else {}
    }

    void commentsElseBlockComment() {
        if (t) {} else /* test */
        {}

        if (t) {} else /* test */
        {}
    }
}
