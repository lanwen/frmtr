package dev.example;

class ChannelAggregator {

    Set<Channel> collectActiveChannels(List<Channel> channels) {
        return channels.stream().collect(Collectors.toUnmodifiableSet());
    }
}
