package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.lanwen.frmtr.Frmtr;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class StatementPrinterTest {

    private static final int GENERATED_CASES = 150;

    @Test
    void formatsLargeStatementTreeWithUnattachedTrailingLineCommentsWithoutGoldenOutput() {
        String source = generatedFrameReaderSource(GENERATED_CASES);
        int lastCase = GENERATED_CASES - 1;

        String formatted = withVerifyDisabled(
            () -> assertTimeoutPreemptively(Duration.ofSeconds(6), () -> Frmtr.format(source))
        );
        String strippedLineIndent = strippedLineIndent(formatted);

        assertThat(formatted).contains(
            "frameBuilder.addCode(cursor.readInt32()); // scalar item 0",
            "break; // variant one 0",
            "fieldSink.acceptUnknown(cursor.currentTag(), cursor.readBytes()); // unknown item " + lastCase,
            "} // end nested catch " + lastCase,
            "return; // stop on end group",
            "fieldSink.finish(cursor.position()); // generated cleanup"
        );
        assertThat(strippedLineIndent).contains(
            """
                // end optional field 0
                break;
                // case field 0
                case 18:
                """.stripTrailing(),
            """
                // end optional field %d
                break;
                // case field %d
                default:
                """.formatted(lastCase, lastCase).stripTrailing(),
            """
                if (!cursor.skipField()) {
                return; // stop on end group
                }
                // end unknown skip
                break;
                """.stripTrailing(),
            """
                } finally {
                fieldSink.finish(cursor.position()); // generated cleanup
                } // end try merge
                }
                // end merge method
                }
                """.stripTrailing()
        );
        assertThat(withVerifyDisabled(() -> Frmtr.format(formatted))).isEqualTo(formatted);
    }

    /**
     * Regression for issue #134: a braceless {@code else} body whose leading {@code //} comments were dropped when the
     * braceless {@code then} body carried two-or-more leading comments. With two then comments, the first bubbles up as
     * the condition's trailing comment, which sent {@code elseChainSeparator} down its condition-trailing branch; that
     * branch returned the bare separator and never rendered the else body's leading block, which the gap slot had already
     * claimed, so those lines were dropped. This stays an inline assertion rather than a {@code format/**} fixture
     * because the braceless-then trigger shape is not perturbation-stable under the corpus reshaping the fixture nets
     * apply (the condition-trailing comment independently explodes the condition under whitespace collapse); the golden
     * else-body-leading-comments fixture covers the perturbation-stable block-then variant.
     */
    @Test
    void keepsBracelessElseBodyLeadingCommentsWhenThenHasTwoLeadingComments() {
        String source = """
            class Min {

                int f(boolean b) {
                    if (b)
                        // keepA1
                        // keepA2
                        return 1;
                    else
                        // dropB1
                        // dropB2
                        return 2;
                }
            }
            """;

        String formatted = Frmtr.format(source);

        assertThat(formatted)
            .contains("// keepA1")
            .contains("// keepA2")
            .contains("// dropB1")
            .contains("// dropB2");
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
    }

    private static <T> T withVerifyDisabled(Supplier<T> action) {
        String previous = System.getProperty(FormatterGuardrails.VERIFY_PROPERTY);
        try {
            System.clearProperty(FormatterGuardrails.VERIFY_PROPERTY);
            return action.get();
        } finally {
            if (previous == null) {
                System.clearProperty(FormatterGuardrails.VERIFY_PROPERTY);
            } else {
                System.setProperty(FormatterGuardrails.VERIFY_PROPERTY, previous);
            }
        }
    }

    private static String strippedLineIndent(String source) {
        return source.lines().map(String::stripLeading).collect(Collectors.joining("\n"));
    }

    private static String generatedFrameReaderSource(int cases) {
        StringBuilder source = new StringBuilder(cases * 1_000);
        source.append(
            """
            class GeneratedFrameReader {
              void mergeFrom(Cursor cursor, FrameBuilder frameBuilder, FieldSink fieldSink) {
                try {
                  while (cursor.hasRemaining()) {
                    switch (cursor.nextTag()) {
            """
        );
        for (int index = 0; index < cases; index++) {
            source.append(frameCase(index));
        }
        source.append(
            """
                      default:
                        if (!cursor.skipField()) {
                          return; // stop on end group
                        } // end unknown skip
                        break; // default branch
                    } // end outer switch
                  } // end outer while
                } catch (ParseSignal signal) {
                  frameBuilder.addUnknown(cursor.currentTag(), signal.partial()); // top partial
                } finally {
                  fieldSink.finish(cursor.position()); // generated cleanup
                } // end try merge
              } // end merge method
            }
            """
        );
        return source.toString();
    }

    private static String frameCase(int index) {
        return """
                      case %d:
                        if (cursor.hasPayload()) {
                          try {
                            while (cursor.hasPackedItems()) {
                              switch (cursor.peekVariant()) {
                                case 1:
                                  frameBuilder.addCode(cursor.readInt32()); // scalar item %d
                                  break; // variant one %d
                                case 2:
                                  frameBuilder.addLabel(cursor.readText()); // label item %d
                                  break; // variant two %d
                                default:
                                  fieldSink.acceptUnknown(cursor.currentTag(), cursor.readBytes()); // unknown item %d
                                  break; // variant default %d
                              } // end nested switch %d
                            } // end packed loop %d
                          } catch (ParseSignal signal) {
                            frameBuilder.addUnknown(cursor.currentTag(), signal.partial()); // partial item %d
                          } // end nested catch %d
                        } // end optional field %d
                        break; // case field %d
            """.formatted(
            10 + (index * 8),
            index,
            index,
            index,
            index,
            index,
            index,
            index,
            index,
            index,
            index,
            index,
            index
        );
    }
}
