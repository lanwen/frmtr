enum ServiceCatalog {
    API_GATEWAY("gateway", 4567),
    EVENT_STREAMS("events", 4570),
    // TODO: Clarify usage for legacy search aliases
    //        LEGACY_SEARCH("search", 4571),
    OBJECT_STORE("storage", 4572),
    NOTIFICATIONS("notify", 4575),
    //        LEGACY_NOTIFICATIONS("", 4578),
    KEY_MANAGER("keys", 4599);

    // Body-level service metadata stays with the member section.

    String catalogName;

    int port;
}
