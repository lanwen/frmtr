class ObjectCreationBlockLambdaCommentInitializerSample {

    void readCurrentUser(DataChannel channel) {
        boolean matched = new ResultReader(channel).read(
            "select current_actor",
            cursor -> {
                cursor.advance();
                String actorName = cursor.text(1);
                // Not every backend appends a realm suffix to the actor name, so trim it before comparing.
                assertThat(actorName).contains("primaryactor");
                return true;
            }
        );
    }
}
