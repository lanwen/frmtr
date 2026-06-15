package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.tooling.ProgressSnapshot;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CliProgressRendererTest {

    private static final String CLEAR_PREVIOUS_LINE = "\u001B[1A\u001B[2K";

    @Test
    void rendersStartedRunningAndFinishedCheckProgress() {
        StringWriter output = new StringWriter();
        CliProgressRenderer renderer = new CliProgressRenderer(new PrintWriter(output, true), "would change");

        renderer.progress(ProgressSnapshot.started(823, 4));
        renderer.progress(
            ProgressSnapshot.running(
                823,
                240,
                7,
                0,
                4,
                List.of(Path.of("src/generated/Huge.java"), Path.of("src/Other.java"))
            )
        );
        renderer.progress(ProgressSnapshot.finished(823, 9, 1, 4));

        assertThat(output.toString()).isEqualTo(
            """
                Processed [0/823 files, 0 would change, 0 failed].
                \u001B[1A\u001B[2K\
                Processed [240/823 files, 7 would change, 0 failed].
                (⠋) src/generated/Huge.java
                \u001B[1A\u001B[2K\u001B[1A\u001B[2K\
                Processed [823/823 files, 9 would change, 1 failed].
                """
        );
    }

    @Test
    void rendersWriteModeFormattedLabel() {
        StringWriter output = new StringWriter();
        CliProgressRenderer renderer = new CliProgressRenderer(new PrintWriter(output, true), "formatted");

        renderer.progress(ProgressSnapshot.started(12, 3));
        renderer.progress(ProgressSnapshot.finished(12, 5, 2, 3));

        assertThat(output.toString()).isEqualTo(
            """
                Processed [0/12 files, 0 formatted, 0 failed].
                \u001B[1A\u001B[2K\
                Processed [12/12 files, 5 formatted, 2 failed].
                """
        );
    }

    @Test
    void advancesSpinnerForRunningSnapshots() {
        StringWriter output = new StringWriter();
        CliProgressRenderer renderer = new CliProgressRenderer(new PrintWriter(output, true), "would change");

        renderer.progress(ProgressSnapshot.running(3, 0, 0, 0, 1, List.of(Path.of("src/A.java"))));
        renderer.progress(ProgressSnapshot.running(3, 1, 1, 0, 1, List.of(Path.of("src/B.java"))));

        assertThat(output.toString()).isEqualTo(
            """
                Processed [0/3 files, 0 would change, 0 failed].
                (⠋) src/A.java
                \u001B[1A\u001B[2K\u001B[1A\u001B[2K\
                Processed [1/3 files, 1 would change, 0 failed].
                (⠙) src/B.java
                """
        );
    }

    @Test
    void replacesDiscoveryStatusWithRunProgress() {
        StringWriter output = new StringWriter();
        CliProgressRenderer renderer = new CliProgressRenderer(new PrintWriter(output, true), "would change");

        renderer.discovering();
        renderer.progress(ProgressSnapshot.started(5, 2));

        assertThat(output.toString()).isEqualTo(
            """
                Discovering Java files...
                \u001B[1A\u001B[2K\
                Processed [0/5 files, 0 would change, 0 failed].
                """
        );
    }

    @Test
    void clearsDiscoveryStatusWhenNoProgressWillFollow() {
        StringWriter output = new StringWriter();
        CliProgressRenderer renderer = new CliProgressRenderer(new PrintWriter(output, true), "would change");

        renderer.discovering();
        renderer.clear();

        assertThat(output.toString()).isEqualTo("Discovering Java files...\n" + CLEAR_PREVIOUS_LINE);
    }
}
