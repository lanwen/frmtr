class TrailingCollectArgIdempotenceSample {

    private int countChannelPairs(List<ChannelRouteDetails> routes, String prefix) {
        int pairs = 0;

        Set<ChannelRouteDetails> inbound = routes.stream()
                .filter(route -> route.isInboundOnly() && route.getChannelUri().startsWith(prefix + ":"))
                .collect(Collectors.toSet());
        Set<ChannelRouteDetails> outbound = routes.stream()
                .filter(route -> route.isOutboundOnly() && route.getChannelUri().startsWith(prefix + ":"))
                .collect(Collectors.toSet());
        return pairs;
    }
}
