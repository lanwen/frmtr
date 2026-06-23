package sample;

final class StringLiteralEqualsInUnaryCondition {

    String resolveUrl(String base) {
        String url = base;
        if (!url.contains("flagOne=")) {
            String separator = url.contains("?") ? "&" : "?";
            url = url + separator + "flagOne=false";
        }
        if (!url.contains("flagTwo=")) {
            url = url + "&flagTwo=true";
        }
        return url;
    }

    String firstSegment(boolean reversed, String entry, String fallback) {
        if (!entry.split("=")[0].equals(fallback)) {
            return reversed ? entry.split("=")[1] : entry.split("=")[0];
        }
        return fallback;
    }
}
