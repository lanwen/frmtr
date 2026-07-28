package sample;

final class ChannelHandlerLookup {

    HandlerResult dispatchActiveHandler() {
        HandlerResult selectedHandlerResult = channelRegistry[channelId] // retry once on timeout
                .resolveActiveChannel(requestContext);
        return selectedHandlerResult;
    }
}
