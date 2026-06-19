class TypeUseArrayAnnotations {

  private String @Nullable [] aliases;

  private @Nullable String @Nullable [] fallbackAliases;

  void invoke(Object @Nullable [] args, @Nullable String @Nullable [] fallbackAliases) {
    String @Nullable [] localAliases;
    @Nullable String @Nullable [] localFallbackAliases;
  }

  void relay(Object @Nullable... args) {
  }
}

record TypeUseArrayRecord(
  String @Nullable [] aliases,
  @Nullable String @Nullable [] fallbackAliases,
  Object @Nullable... args
) {
}
