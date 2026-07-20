class LogCleaner {

    void groupSegments() {
        while (
            !segments.isEmpty()
            && logSize + segments.get(0).size() <= maxSize
            //if first segment size is 0, we don't need to do the index offset range check.
            //this will avoid empty log left every 2^31 message.
            && (segments.get(0).size() == 0
                || lastOffsetForFirstSegment(segments, firstUncleanableOffset) - baseOffset <= Integer.MAX_VALUE)
        ) {
            group.add(0, segments.get(0));
        }
    }
}
