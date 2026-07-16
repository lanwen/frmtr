class MetadataRequest {

    public boolean isAllTopics() {
        return (data.topics() == null) || (data.topics().isEmpty() && version() == 0)
            // In version 0, an empty topic list indicates
        ;
        // "request metadata for all topics."
    }
}
