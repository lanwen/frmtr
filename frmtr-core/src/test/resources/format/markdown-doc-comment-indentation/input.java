/// Registry of routing weights keyed by region.
/// Each entry is recomputed lazily on first lookup.
public class WeightRegistry {

    ////
    //// If you change the bucket layout below be sure
    //// to update the "layout" descriptor word
    ////
    protected int bucketCount;

    /// Cache of resolved weights.
    ///
    /// The map is rebuilt whenever the layout changes:
    ///     key   = region identifier
    ///     value = normalized weight
    private final Map<String, Double> resolvedWeights;

    //
    // Normal line-comment control block: this one is already
    // idempotent and must keep its existing indentation.
    //
    private boolean dirty;

    /// Resets the registry to its empty state.
    void reset() {
        bucketCount = 0;
        dirty = true;
    }
}
