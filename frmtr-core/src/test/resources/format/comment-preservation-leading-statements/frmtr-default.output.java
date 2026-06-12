class LeadingStatementCommentSample {

    void parse(InputStream stream) {
        // keep first note
        // keep second note
        try {
            stream.read();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}

interface InputStream {
    int read() throws Exception;
}
