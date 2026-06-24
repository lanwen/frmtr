class CacheRegistry {

    /* The set of cache regions the partition is bound to,
     * spanning several backing stores at once,
     * which require a coordinated flush. */
    int boundRegions;

    /* The pending eviction queue,
     * which require a coordinated flush. */
    int pendingEvictions;

    /* A leading sentence here,
     * a middle sentence here,
     * a closing sentence here.
     */
    int alignedClose;
}
