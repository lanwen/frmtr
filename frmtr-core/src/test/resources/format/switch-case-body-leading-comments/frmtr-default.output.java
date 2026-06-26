package fixtures.switching;

class DispatchTable {

    String classify(int regionCode) {
        switch (regionCode) {
            case 12:
                // resolve the regional payload eagerly because the
                // downstream reader expects it before any header bytes
                return "north";
            case 18: // single inline note stays on the label line
                return "south";
            case 24:
            case 30:
                // shared arm: fold both region codes into one bucket and
                // keep the explanatory note as its own leading lines
                return "central";
            default:
                // unknown regions fall back to the catch-all bucket so the
                // caller never sees a null classification result
                return "unknown";
        }
    }
}
