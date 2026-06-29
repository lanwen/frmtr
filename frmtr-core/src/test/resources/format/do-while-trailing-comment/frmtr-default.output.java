package fixtures;

class DoWhileTrailingComment {

    int multilineBlockBody(int n) {
        do {
            n = compute(n);
        } while (n == 0); // multiline body: comment attaches to the condition
        return n;
    }

    int singleLineBlockBody(int n) {
        do {
            n = compute(n);
        } while (n == 0); // single-line body: comment attaches to the statement
        return n;
    }

    int singleStatementBody(int n) {
        do n = compute(n); while (n == 0); // single-statement body
        return n;
    }

    int compute(int n) {
        return n - 1;
    }
}
