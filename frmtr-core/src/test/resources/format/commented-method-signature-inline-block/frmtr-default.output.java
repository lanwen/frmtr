class SignatureComments<K, V> {

    @Override
    public /* NOTE(planner): stable */ String toString() {
      return displayName;
    }

    @Override
    public /* NOTE(planner): stable */ ImmutableList<V> get(K key) {
        // This cast is safe as the constructor controls the map.
        ImmutableList<V> list = (ImmutableList<V>) valuesByKey.get(key);
        return list == null ? ImmutableList.of() : list;
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
      @Nullable Object @Nullable ... messageArguments
    ) {
      if (!enabled) {
        throw new IllegalStateException(format(messageTemplate, messageArguments));
      }
    }
}
