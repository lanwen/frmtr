package fixtures.switching;

class SegmentRouter {

    String routeSegment(int segmentCode) {
        switch (segmentCode) {
            case 2: // SEG-3
                return "header";
            case 3: // SEG-4
                return "encoding";
            case 5: // SEG-3
                return "control";
            case 7: // SEG-3
                return "payload";
            /** Images **/
            case 11:
                return "images";
            /* Containers */
            case 13:
                return "containers";
            default:
                return "unknown";
        }
    }
}
