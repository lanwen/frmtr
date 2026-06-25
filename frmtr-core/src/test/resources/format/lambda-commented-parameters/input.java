package sample;

import java.util.function.BiConsumer;

final class CommentedLambdaParams {

    BiConsumer<Payload, Context> handler(Router router) {
        return (/* inbound */ incomingPayloadEnvelope, /* correlation */ correlationContext) -> incomingPayloadEnvelope.acknowledge(correlationContext);
    }
}
