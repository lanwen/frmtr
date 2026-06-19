@NullMarked
class TypeUseArrayAnnotations {

  private String @Nullable [] aliases;

  private @Nullable String @Nullable [] fallbackAliases;

  private List<? extends @Nullable Number> limitedNumbers;

  private List<? super @Nullable String> fallbackLabels;

  private String[] @Nullable [] rows;

  private @Nullable String @Nullable [] @Nullable [] matrix;

  void invoke(Object @Nullable [] args, @Nullable String @Nullable [] fallbackAliases) {
    String @Nullable [] localAliases;
    @Nullable String @Nullable [] localFallbackAliases;
    String[] @Nullable [] localRows;
    @Nullable String @Nullable [] @Nullable [] localMatrix;
  }

  void relay(Object @Nullable... args) {
  }

  void relayLabels(@Nullable String... labels) {
  }

  @NullUnmarked
  void relayPlain(String label) {
  }
}

record TypeUseArrayRecord(
  String @Nullable [] aliases,
  @Nullable String @Nullable [] fallbackAliases,
  List<? extends @Nullable Number> limitedNumbers,
  List<? super @Nullable String> fallbackLabels,
  String[] @Nullable [] rows,
  @Nullable String @Nullable [] @Nullable [] matrix,
  Object @Nullable... args,
  @Nullable String... labels
) {
}
