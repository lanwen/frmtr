package sample;

import java.util.NavigableMap;

final class SegmentRegistry {

    SegmentRegistry(
            RegionContext regionContext,
            // segments are keyed by closing offset
            NavigableMap<Long, Segment> segments
    ) {
        this.regionContext = regionContext;
        this.segments = segments;
    }

    SegmentRegistry(
            RegionContext regionContext,
            // entries are keyed by closing offset
            NavigableMap<Long, Segment> primary,
            // entries are keyed by closing offset
            NavigableMap<Long, Segment> secondary
    ) {
        this.regionContext = regionContext;
        this.segments = primary;
    }

    SegmentRegistry(RegionContext regionContext, NavigableMap<Long, Segment> segments, long limit) {
        this.regionContext = regionContext;
        this.segments = segments;
    }

    private final RegionContext regionContext;

    private final NavigableMap<Long, Segment> segments;
}
