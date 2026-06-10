class ObjectCreationStatementArgumentSample {
    void notifyAccepted(Reference<Command<Result<Item>>> command, String serial, Reply<Item> reply) {
        sink.tell(
            new InternalSignal.AcceptedSignal(command, serial, reply.getValue())
        );
        sink.recover(
            FixtureRecords.started("sample"),
            new FixtureTransitionRecords.PersistentBehavior.Event.CancelRequested(FixtureOutcomes.REQUEST_WAS_CANCELLED),
            FixtureRecords.completed()
        );
    }
}
