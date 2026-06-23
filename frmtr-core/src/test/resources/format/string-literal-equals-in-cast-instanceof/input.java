package sample;

import java.util.Map;

final class StringLiteralEqualsInCastInstanceof {

    String pickHeader(boolean primary, Map<String, Object> registry) {
        String resolved = primary
            ? (String) registry.get("modeOne=")
            : (String) registry.get("modeTwo=fallback");
        return resolved;
    }

    boolean isStringEntry(Map<String, Object> registry) {
        boolean matched = registry.get("keyOne=value") instanceof String
            ? registry.get("keyTwo=value") instanceof String
            : false;
        return matched;
    }
}
