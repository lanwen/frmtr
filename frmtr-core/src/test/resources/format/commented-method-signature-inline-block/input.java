class SignatureComments {

  @Override
  public /* NOTE(planner): stable */ String toString() {
    return displayName;
  }

  public /* NOTE(planner): stable */ ImmutableList<Value> get(Key key) {
    return valuesByKey.get(key);
  }

  @Override
  public /*non-final for translator*/ boolean tryAdvance(Consumer<? super Value> action) {
    while (true) {
      if (prefix != null && prefix.tryAdvance(action)) {
        return true;
      } else {
        prefix = null;
      }
      if (!source.tryAdvance(item -> prefix = mapper.apply(item))) {
        return false;
      }
    }
  }

  public static void checkState(
      boolean enabled,
      /* NOTE(planner): migrate when routes are generated. */
      @Nullable String messageTemplate,
      @Nullable Object @Nullable ... messageArguments) {
    if (!enabled) {
      throw new IllegalStateException(format(messageTemplate, messageArguments));
    }
  }
}
