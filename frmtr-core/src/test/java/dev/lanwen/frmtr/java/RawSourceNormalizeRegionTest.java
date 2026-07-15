package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the single-scan {@link RawSource#normalizeOutsideRegion(CharSequence)} to the collapse / {@code =}-spacing /
 * re-collapse / opener-closer-trim regex composition it replaced, so the hand-rolled scanner cannot drift from that
 * reference behavior — including the operator-adjacency and double-space-then-recollapse edge cases.
 */
class RawSourceNormalizeRegionTest {

    private final RawSource rawSource = new RawSource(FormatterOptions.defaults());

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern ASSIGN_EQUALS = Pattern.compile("(?<![-+*/%^&|=!<>])\\s*=\\s*(?![=])");

    private static final Pattern SPACE_AFTER_OPENER = Pattern.compile("([(\\[]) ");

    private static final Pattern SPACE_BEFORE_CLOSER = Pattern.compile(" ([)\\]])");

    /** Reference semantics: the five sequential regex passes {@code flushOutside} used before the single-scan rewrite. */
    private static String reference(String region) {
        String collapsed = WHITESPACE.matcher(region).replaceAll(" ");
        String normalized = ASSIGN_EQUALS.matcher(collapsed).replaceAll(" = ");
        String reCollapsed = WHITESPACE.matcher(normalized).replaceAll(" ");
        String afterOpener = SPACE_AFTER_OPENER.matcher(reCollapsed).replaceAll("$1");
        return SPACE_BEFORE_CLOSER.matcher(afterOpener).replaceAll("$1");
    }

    @Test
    void matchesReferenceOnStructuralEdgeCases() {
        String[] cases = {
            "",
            " ",
            "   ",
            "\t\n\f",
            "a = b",
            "a=b",
            "a =b",
            "a= b",
            "x=y=z",
            "==",
            "===",
            "a==b",
            "a == b",
            "a ==b",
            "a== b",
            "= =",
            "= = =",
            "+=",
            "a += b",
            "<=",
            ">>=",
            "a >>= b",
            "!=",
            "a<=b",
            "=x",
            "x=",
            "=",
            "(=)",
            "( =x)",
            "( a )",
            "[ x ]",
            "( )",
            "((a))",
            "a| =b",
            "foo( arg )",
            "foo(\n    arg\n)",
            "call( a, b )",
            "map[ key ]",
            "return x + y;",
            "/* a=b === c */",
            "a  =  b",
            "  leading",
            "trailing  ",
        };
        for (String input : cases) {
            assertThat(rawSource.normalizeOutsideRegion(input))
                    .as("input=%s", visualize(input))
                    .isEqualTo(reference(input));
        }
    }

    @Test
    void matchesReferenceOnRandomizedInput() {
        char[] alphabet = {
            'a',
            'b',
            '1',
            '.',
            ',',
            ';',
            ' ',
            ' ',
            ' ',
            '\t',
            '\n',
            '\r',
            '\u000B',
            '\f',
            '=',
            '=',
            '(',
            ')',
            '[',
            ']',
            '+',
            '-',
            '*',
            '/',
            '%',
            '^',
            '&',
            '|',
            '!',
            '<',
            '>',
        };
        Random random = new Random(20260716L);
        for (int iteration = 0; iteration < 200_000; iteration++) {
            StringBuilder builder = new StringBuilder();
            int length = random.nextInt(24);
            for (int position = 0; position < length; position++) {
                builder.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String input = builder.toString();
            assertThat(rawSource.normalizeOutsideRegion(input))
                    .as("input=%s", visualize(input))
                    .isEqualTo(reference(input));
        }
    }

    private static String visualize(String text) {
        return text.replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\u000B", "\\u000B")
                .replace("\f", "\\f");
    }
}
