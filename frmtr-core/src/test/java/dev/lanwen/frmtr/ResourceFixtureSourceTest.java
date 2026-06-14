package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;

/**
 * Exercises the input-only discovery mode of {@link ResourceFixtureSource}.
 */
final class ResourceFixtureSourceTest {

    /**
     * Drives the {@link FixtureInput} {@code @ArgumentsSource} mode over the {@code unsupported} fixtures, which carry
     * {@code input.java} and {@code error.txt} but deliberately no {@code frmtr-<variant>.output.java}. The behavior
     * under test is the "no output companion required" contract: the {@link FormatFixture} mode would fail here
     * demanding an output, while the input-only mode must still discover every fixture's source.
     */
    @ParameterizedTest(name = "{0}")
    @ResourceFixtureSource(glob = "unsupported/**/input.java")
    void inputOnlyModeDiscoversFixturesWithoutOutputCompanions(FixtureInput fixture) {
        assertThat(fixture.name()).isNotBlank();
        assertThat(fixture.source()).isNotBlank();
    }
}
