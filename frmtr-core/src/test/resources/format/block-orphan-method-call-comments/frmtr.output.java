package dev.example;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

class Demo {

    void method() {
        // Scenario: reduce capacity to 1/3 from Friday 7pm to Sunday 7pm
        // Default pool: min=9, max=30
        // Weekend pool: min=3, max=10
        var schedule =
            new Config.Schedule(
                ZoneId.of("Europe/Madrid"),
                List.of(
                    new Config.Schedule.Shift("0 0 19 * * 5", 3, 10), // Friday 7pm: start weekend mode
                    new Config.Schedule.Shift("0 0 19 * * 0", Config.UNSET, Config.UNSET)
                    // Sunday 7pm: restore defaults
                )
            );
        var matcher = new Matcher(schedule);

        // Friday January 24, 2025 at 6pm Madrid time (17:00 UTC in winter) - before weekend mode
        // The most recent shift is from the previous Sunday (restore defaults), so -1/-1
        var before = matcher.match(Instant.parse("2025-01-24T17:00:00Z")).orElseThrow();
        assertThat(before.shift().minSize()).isEqualTo(Config.UNSET);
        var combined = combine(
            // before first call arg
            alpha(),
            beta()
            // after last call arg
        );
        var created = new Box(
            // before first constructor arg
            alpha(),
            beta()
            // after last constructor arg
        );
        var chained = receiver
            .step(
                // before first chain arg
                alpha(),
                beta()
                // after last chain arg
            )
            .finish();
    }
}
