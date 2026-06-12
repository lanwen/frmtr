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

    void two() // alpha
    {}

    void three() // alpha
    // beta
    {}

    void four() // alpha
    // beta
    /* gamma */
    {}

    void five() {} // alpha

    void fiveBis() { // alpha
      int sequenceNumber;
    }

    void six /* alpha */() {}

    void seven() /* alpha */
    /* beta */
    {}

    void eight() /* alpha */
    // beta
    {}

    void nine() /* alpha */
    {}

    void one(
        String accountName
    ) {}

    void two(String accountName) // alpha
    {}

    void three(String accountName) // alpha
    // beta
    {}

    void four(
      // alpha
      String accountName
    ) // beta
    /* gamma */
    {}

    void five(
      String accountName // alpha
    ) {}

    void six(String accountName /* alpha */) {}

    void seven(
      /* alpha */
      String accountName
    ) /* beta */
    {}

    void eight(
      /* alpha */
      String accountName
    ) // beta
    {}

    void nine(String accountName) /* alpha */
    {}
}
