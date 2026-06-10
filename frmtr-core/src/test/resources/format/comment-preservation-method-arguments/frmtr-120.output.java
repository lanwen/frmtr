class MethodArgumentCommentSample {

    void configure(Source source) {
        var selectedValue = Defaults.withVeryLongFallbackSelectionName(
            source.primaryValue(),
            // keep fallback reason
            Defaults.FALLBACK_VALUE
        );
    }
}

class Source {

    String primaryValue() {
        return "primary";
    }
}

class Defaults {

    static final String FALLBACK_VALUE = "fallback";

    static String withVeryLongFallbackSelectionName(String primary, String fallback) {
        return primary == null ? fallback : primary;
    }
}
