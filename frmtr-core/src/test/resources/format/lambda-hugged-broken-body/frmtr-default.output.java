package sample;

final class HuggedBrokenLambdaBody {

    void hugsNoArgBody(EventRouter router) {
        router.onEach(
            (
                firstIncomingEventPayloadEnvelopeReference,
                secondCorrelatedDownstreamEventPayloadEnvelope,
                thirdAuditMetadataTrackingComplianceRecord
            ) -> firstIncomingEventPayloadEnvelopeReference.acknowledge()
        );
    }

    void breaksBodyWithArguments(EventRouter router) {
        router.onEach(
            (
                firstIncomingEventPayloadEnvelopeReference,
                secondCorrelatedDownstreamEventPayloadEnvelope,
                thirdAuditMetadataTrackingComplianceRecord
            ) ->
                firstIncomingEventPayloadEnvelopeReference.acknowledge(secondCorrelatedDownstreamEventPayloadEnvelope)
        );
    }
}
