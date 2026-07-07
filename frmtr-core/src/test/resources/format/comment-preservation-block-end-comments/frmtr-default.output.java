class EmptyCommentBlock {}

class SingleLineCommentBlock {
    // alpha
}

class StackedLineCommentBlock {
    // alpha
    // beta
}

class MixedLineBlockCommentBlock {
    // alpha
    // beta
    /* gamma */
}

class TrailingLineCommentBlock {
    // alpha
}

class CompactBlockCommentBlock {
    /* alpha */
}

class StackedBlockCommentBlock {
    /* alpha */
    /* beta */
}

class MixedBlockLineCommentBlock {
    /* alpha */
    // beta
}

class MemberCommentBlock {

    // alpha
    // beta
    int sequenceNumber;

    // one
    // two
    /* three */
}

class ParameterCommentCases {

    void one() {}

    void two() {}

    void three() {}

    void four() {}

    void five() {} // alpha

    void fiveBis() {
        // alpha
        int sequenceNumber;
    }

    void six /* alpha */() {}

    void seven() {}

    void eight() {}

    void nine() {}

    void one(String accountName) {}

    void two(String accountName) {}

    void three(String accountName) {}

    void four(
      // alpha
      String accountName
    ) {}

    void five(
      String accountName // alpha
    ) {}

    void six(String accountName /* alpha */) {}

    void seven(
      /* alpha */
      String accountName
    ) {}

    void eight(
      /* alpha */
      String accountName
    ) {}

    void nine(String accountName) {}
}
