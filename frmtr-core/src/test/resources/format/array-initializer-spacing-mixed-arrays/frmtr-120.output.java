class ArraySamples {

    boolean[] skip = new boolean[candidates.length];

    Class<?> lookupTableClass = new CachedLookupTable<
        RefreshPolicyKey
    >[1].getClass();

    Class<?> rawLookupTableClass = new CachedLookupTable[1000000000000000000].getClass();

    Class<?> seededLookupTableClass = new CachedLookupTable[] {
        new CachedLookupTable(),
    }.getClass();

    String[] EMPTY_STATUSES = {
        // nothing yet
    };

    Weather currentWeather = enumValues[(currentWeather.ordinal() + 1) % enumValues.length];
}
