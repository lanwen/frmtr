class AnnotationBlockCommentGap {

    int port;

    @Deprecated
    /*
        Since version 0.11, the service exposes all APIs on a single port.
     */
    public int sourcePort() {
        return port;
    }

    @Deprecated
    public int firstPassPort() {
        return port;
    }
}
