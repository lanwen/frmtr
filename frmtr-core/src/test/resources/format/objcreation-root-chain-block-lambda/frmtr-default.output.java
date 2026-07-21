class ChannelSupervisor {

    void observeShutdown(Channel channel) {
        new ConnectionStateObserver(channel, state -> {
            switch (state) {
                case SHUTDOWN -> channelDisposable.dispose();
                default -> channel.acknowledge(state);
            }
        }).run();
    }

    void observeRetries(Channel channel) {
        new ConnectionStateObserver(channel, state -> {
            retryCounter.increment();
            channel.reconnect(state);
        }).run();
    }

    void observeSingleStatement(Channel channel) {
        new ConnectionStateObserver(channel, state -> {
            channel.acknowledge(state);
        }).run();
    }
}
