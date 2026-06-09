class ObjectCreationStatementArgumentSample {

    void notifyAccepted(Reference<Command<Result<Item>>> command, String serial, Reply<Item> reply) {
        sink.tell(
            new InternalSignal.AcceptedSignal(command, serial, reply.getValue())
        );
    }
}
