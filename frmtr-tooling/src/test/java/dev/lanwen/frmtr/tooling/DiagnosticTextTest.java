package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DiagnosticTextTest {

    @Test
    void reconstructsPlainTextFromStyledSpans() {
        DiagnosticText diagnostic = new DiagnosticText(
            List.of(
                new DiagnosticLine(
                    List.of(
                        new DiagnosticSpan("┌─ ", DiagnosticStyle.BORDER_GUTTER),
                        new DiagnosticSpan("Unable to parse Java source:", DiagnosticStyle.ERROR_TEXT)
                    )
                ),
                new DiagnosticLine(
                    List.of(
                        new DiagnosticSpan("│ ", DiagnosticStyle.BORDER_GUTTER),
                        new DiagnosticSpan("12", DiagnosticStyle.LINE_NUMBER),
                        new DiagnosticSpan("  ", DiagnosticStyle.BORDER_GUTTER),
                        new DiagnosticSpan("int value =", DiagnosticStyle.SOURCE_TEXT)
                    )
                ),
                new DiagnosticLine(
                    List.of(
                        new DiagnosticSpan("│    ", DiagnosticStyle.BORDER_GUTTER),
                        new DiagnosticSpan("┌──────────^", DiagnosticStyle.POINTER)
                    )
                ),
                new DiagnosticLine(List.of(new DiagnosticSpan("│ ⋮", DiagnosticStyle.GAP))),
                new DiagnosticLine(List.of(new DiagnosticSpan("└─", DiagnosticStyle.BORDER_GUTTER)))
            )
        );

        assertThat(diagnostic.plainText()).isEqualTo(
            String.join(
                System.lineSeparator(),
                "┌─ Unable to parse Java source:",
                "│ 12  int value =",
                "│    ┌──────────^",
                "│ ⋮",
                "└─"
            )
        );
    }

    @Test
    void copiesInputListsAndExposesImmutableViews() {
        List<DiagnosticSpan> spans = new ArrayList<>();
        spans.add(new DiagnosticSpan("12", DiagnosticStyle.LINE_NUMBER));
        DiagnosticLine line = new DiagnosticLine(spans);
        spans.add(new DiagnosticSpan("class Demo {}", DiagnosticStyle.SOURCE_TEXT));

        List<DiagnosticLine> lines = new ArrayList<>();
        lines.add(line);
        DiagnosticText diagnostic = new DiagnosticText(lines);
        lines.add(new DiagnosticLine(List.of(new DiagnosticSpan("^", DiagnosticStyle.POINTER))));

        assertThat(line.spans()).containsExactly(new DiagnosticSpan("12", DiagnosticStyle.LINE_NUMBER));
        assertThat(diagnostic.lines()).containsExactly(line);
        assertThatThrownBy(() -> line.spans().add(new DiagnosticSpan("x", DiagnosticStyle.ERROR_TEXT)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> diagnostic.lines().add(
                new DiagnosticLine(List.of(new DiagnosticSpan("x", DiagnosticStyle.ERROR_TEXT)))
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullModelParts() {
        assertThatNullPointerException().isThrownBy(() -> new DiagnosticText(null));
        assertThatNullPointerException().isThrownBy(() -> new DiagnosticLine(null));
        assertThatNullPointerException().isThrownBy(() -> new DiagnosticSpan(null, DiagnosticStyle.ERROR_TEXT));
        assertThatNullPointerException().isThrownBy(() -> new DiagnosticSpan("message", null));
    }
}
