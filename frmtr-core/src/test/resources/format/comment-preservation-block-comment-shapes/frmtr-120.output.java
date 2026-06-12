// Bug Fix: #279 - See also https://prettier.io/docs/en/rationale.html#comments
class CommentBugFixExamples {

    /*
     * comment
     */
    void renderPaddedBlockComment() {}

    /*
     * comment
     */
    void renderFlushBlockComment() {}

    /*
     * comment
     */
    void renderAlignedBlockComment() {}

    /*
     * comment
     */
    void renderIndentedBlockComment() {}

    /*
      * line 1
              line 2
             */
    void renderMultilineBlockComment() {}

    /*

                *line 2
               */
    void renderCommentWithLeadingBlank() {}

    public static final List<Object> COMMENT_ITEMS = Collections.unmodifiableList(
        Arrays.asList(
            // a
            // b
            // c
            // d
        )
    );

    public static final List<Object> COMMENT_ITEMS_WITH_TRAILER = Collections.unmodifiableList(
        Arrays.asList(
            // a
            // b
            // c
            // d
            /*e*/
        )
    );

    void danglingArgumentList() {
        a(
            // a
        );
    }
}
