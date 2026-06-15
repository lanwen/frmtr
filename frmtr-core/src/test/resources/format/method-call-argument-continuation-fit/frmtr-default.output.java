class MethodCallArgumentContinuationFitSample {

    void verifyCollectorOutput(String formatted, String strippedLineIndent, int lastEntry) {
        assertThat(formatted).contains(
            "recordWriter.addCode(cursor.readInt32()); // scalar item 0",
            "break; // variant one 0",
            "outputSink.acceptUnknown(cursor.currentTag(), cursor.readBytes()); // unknown item " + lastEntry,
            "} // end nested catch " + lastEntry,
            "return; // stop on end group",
            "outputSink.finish(cursor.position()); // generated cleanup"
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
                """.formatted(lastEntry, lastEntry).stripTrailing(),
            """
                if (!cursor.skipField()) {
                return; // stop on end group
                }
                // end unknown skip
                break;
                """.stripTrailing(),
            """
                } finally {
                outputSink.finish(cursor.position()); // generated cleanup
                } // end try merge
                }
                // end merge method
                }
                """.stripTrailing()
        );
    }

    private static String generatedCase(int index) {
        return """
                      case %d:
                        if (cursor.hasPayload()) {
                          try {
                            while (cursor.hasPackedItems()) {
                              switch (cursor.peekVariant()) {
                                case 1:
                                  recordWriter.addCode(cursor.readInt32()); // scalar item %d
                                  break; // variant one %d
                                case 2:
                                  recordWriter.addLabel(cursor.readText()); // label item %d
                                  break; // variant two %d
                                default:
                                  outputSink.acceptUnknown(cursor.currentTag(), cursor.readBytes()); // unknown item %d
                                  break; // variant default %d
                              } // end nested switch %d
                            } // end packed loop %d
                          } catch (ParseSignal signal) {
                            recordWriter.addUnknown(cursor.currentTag(), signal.partial()); // partial item %d
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
