package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.lanwen.frmtr.Frmtr;
import java.time.Duration;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class StatementPrinterTest {

    private static final int GENERATED_CASES = 150;

    @Test
    void formatsLargeStatementTreeWithUnattachedTrailingLineCommentsWithoutGoldenOutput() {
        String source = generatedFrameReaderSource(GENERATED_CASES);
        int lastCase = GENERATED_CASES - 1;

        String formatted = assertTimeoutPreemptively(Duration.ofSeconds(6), () -> Frmtr.format(source));
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
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
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
