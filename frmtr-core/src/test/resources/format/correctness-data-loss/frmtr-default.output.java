import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@Fallback
// Keep this leading type note.
class DataLossCases {

    enum Marker {
        @Fallback
        UNKNOWN("unknown"),
        KNOWN("known"),
    }

    enum Plan {
        TRIAL, // Default plan for new accounts

        @Fallback
        FREE, // Allows extended feature access without billing setup
        PAID, // Requires a configured payment method
        PARTNER, // Paid plan with externally managed billing
        ANNUAL, // Annual paid plan below enterprise tier
    }

    enum CaptureMode {
        SNAPSHOT("snapshot") {

            @Override
            String encode(Recorder recorder, String source) throws IOException {
                String output = "/tmp/capture.snapshot";
                recorder.exec("encoder", "-i", source, output);
                return output;
            }
        },
        ARCHIVE("archive") {

            @Override
            String encode(Recorder recorder, String source) throws IOException {
                String output = "/tmp/capture.archive";
                recorder.exec(
                    "encoder",
                    "-i",
                    source,
                    "-codec",
                    "copy",
                    output
                );
                return output;
            }
        };

        abstract String encode(Recorder recorder, String source) throws IOException;
    }

    enum OverrideShape {
        EMPTY {},
        COMMENT_ONLY {
            // Explicit body marker without members.
        },
    }

    enum OverrideTailComment {
        TRAILING_BODY {}, // Keep this tail note.
    }

    record Payload(
        @NotNull Map<@Sized(min = 2) String, @Sized(max = 10) String> values,
        // keep this comment with the type
        String typeCommentedName,
        String note // keep this comment with the component
    ) {}

    void run(Api api, Auth auth) {
        Zone zone = new Zone(
            api,
            auth,
            "name"
        ) // restart note
                .withProperty("retry", "60s")
                .withMinimumRunningDuration(Duration.ZERO);
    }

    String pick(String value) {
        return switch (value) {
            case "a" -> "A";
            default -> throw new IllegalStateException("Unexpected endpoint scheme: " + value);
        };
    }

    boolean guarded(Object error) {
        return error instanceof ReplyEnvelope.Message message && ReplyCodes.NOT_FOUND.equals(message.toString());
    }
}

class Api {}

class Auth {}

class Recorder {

    void exec(String... command) {}
}

class Zone {

    Zone(Api api, Auth auth, String name) {}

    Zone withProperty(String key, String value) {
        return this;
    }

    Zone withMinimumRunningDuration(Duration duration) {
        return this;
    }
}

class ReplyEnvelope {

    static class Message {}
}

class ReplyCodes {

    static final String NOT_FOUND = "";
}

@interface Fallback {}

@interface NotNull {}

@interface Sized {
    int min() default 0;

    int max() default 0;
}
