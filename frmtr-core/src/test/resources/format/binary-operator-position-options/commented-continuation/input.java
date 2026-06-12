class CommentedContinuation {
    void choose() {
        boolean value = primaryReady || secondaryReady >> 1 // first
            // second
            // third
            || fallbackReady;
    }
}
