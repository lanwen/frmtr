class AnnotationBlockCommentGap {

    int port;

    @Deprecated
    /*
        Since version 0.11, the service exposes all APIs on a single port.
     */
    public int sourcePort() {
        return port;
    }

    @Deprecated /*
            Since version 0.11, the service exposes all APIs on a single(4566 ) port.*/ public int firstPassPort () {
      return port;
    }
}
