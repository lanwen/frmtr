package sample;

final class ChainFanLoneTrailingSelectorTail {

    OrderPipeline keepsFanWhenMultipleTrailingSelectorsRemain(OrderPipeline orderPipeline) {
        return orderPipeline.stage(FulfillmentStage.INTAKE)
                .validate()
                .commit();
    }

    StepProbe keepsFanWhenTrailingSelectorHasArguments(
            StepProbe probe,
            SessionReader sessionReader,
            Principal principal
    ) {
        return probe.withVirtualTime(() -> sessionReader.findSessions(
            principal.groupId(),
            Source.LOCAL,
            principal,
            null
        ))
                .expectNextCount(4);
    }
}
