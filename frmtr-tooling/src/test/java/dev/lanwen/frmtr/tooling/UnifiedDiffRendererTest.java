package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class UnifiedDiffRendererTest {
    @Test
    void rendersPatchLikeDiffByDefault() throws Exception {
        String diff = UnifiedDiffRenderer.render(Path.of("Demo.java"), "class Demo{int value;}", "class Demo {}\n");

        assertThat(diff)
                .contains("diff --git origin frmtr\n")
                .contains("--- origin\n+++ frmtr\n")
                .contains("-class Demo{int value;}")
                .contains("+class Demo {}")
                .doesNotContain("⋮");
    }

    @Test
    void lineWidthRulerDecoratesNearLinesAndNeighboringHunkRowsIncludingBlankRows() {
        String diff = """
                diff --git origin frmtr
                --- origin
                +++ frmtr
                @@ -1,5 +1,5 @@
                 before
                -12345678901234567890
                +12345678901234567890123
                """
                + " \n"
                + """
                 after
                +short
                \\ No newline at end of file
                """;

        String decorated = UnifiedDiffRenderer.decorateWithLineWidthRuler(diff, 20);
        String hunkHeader = "@@ -1,5 +1,5 @@";
        String numberedHunkHeader = hunkHeader + " ".repeat(21 - hunkHeader.length()) + "⋮ 20\n";
        String topNeighbor = " before" + " ".repeat(20 - "before".length()) + "⋮\n";
        String blankNeighbor = " ".repeat(21) + "⋮\n";
        String overflow = " ".repeat(21) + "⋮+3\n";

        assertThat(decorated).isEqualTo("""
                diff --git origin frmtr
                --- origin
                +++ frmtr
                """
                + numberedHunkHeader
                + topNeighbor
                + """
                -12345678901234567890⋮
                +12345678901234567890⋮123
                """
                + overflow
                + blankNeighbor
                + """
                 after
                +short
                \\ No newline at end of file
                """);
    }
}
