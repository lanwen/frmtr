class BrokenCallTrailingComment {

    void configureStreamsGroupTimeouts() {
        configs.put(GroupCoordinatorConfig.STREAMS_GROUP_MAX_HEARTBEAT_INTERVAL_MS_CONFIG, GroupCoordinatorConfig.STREAMS_GROUP_SESSION_TIMEOUT_MS_DEFAULT); // required
        createConfig(configs);
    }
}
