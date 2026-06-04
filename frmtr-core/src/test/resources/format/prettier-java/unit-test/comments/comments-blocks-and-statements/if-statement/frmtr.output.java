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
        if (t) /* test*/
        {}
        if (t) {}
        if (t) {}
    }

    void commentsElseLineComment() {
        if (t) {} else {}
        if (t) {} else {}
    }

    void commentsElseBlockComment() {
        if (t) {} else {}
        if (t) {} else {}
    }
}
