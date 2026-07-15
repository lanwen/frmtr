package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Pins the single-pass {@link RawSource#stripTrailingHorizontalWhitespace(String)} to the line-split/strip/join
 * semantics it replaced, so the hand-rolled scanner cannot drift from the reference behavior.
 */
class RawSourceTest {

    private final RawSource rawSource = new RawSource(FormatterOptions.defaults());

    /** Reference semantics: split like {@code String.lines()}, strip each line's trailing whitespace, rejoin. */
    private static String reference(String text) {
        return text.lines()
                .map(String::stripTrailing)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    @Test
    void matchesReferenceOnStructuralEdgeCases() {
        String[] cases = {
            "",
            "a",
            "a   ",
            "a\t\t",
            "a\n",
            "a\nb",
            "a   \nb   ",
            "a\n\n",
            "\n",
            "\n\n",
            "   ",
            "  \n  ",
            "a\r\nb",
            "a\rb",
            "a\r\n",
            "a\r",
            "linewithverticaltabs   ",
            "trailing form feed\f",
            "  leading kept, trailing dropped   \n\tindented body\t\t",
        };
        for (String input : cases) {
            assertThat(rawSource.stripTrailingHorizontalWhitespace(input))
                    .as(
                        "input=%s",
                        input.replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t")
                    )
                    .isEqualTo(reference(input));
        }
    }

    @Test
    void matchesReferenceOnRandomizedInput() {
        char[] alphabet = {
            'a',
            'b',
            ' ',
            ' ',
            '\t',
            '\n',
            '\r',
            '\f',
        };
        Random random = new Random(20260715L);
        for (int iteration = 0; iteration < 20_000; iteration++) {
            StringBuilder builder = new StringBuilder();
            int length = random.nextInt(40);
            for (int position = 0; position < length; position++) {
                builder.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String input = builder.toString();
            assertThat(rawSource.stripTrailingHorizontalWhitespace(input)).isEqualTo(reference(input));
        }
    }
}
