class RecordImplementsSample {

    interface InternalSignal {
        record CompletedSignal(
            Reference<Result<Item>> sink,
            @Nullable Marker marker,
            Item item
        ) implements InternalSignal {}

        record AcceptedSignal(
            Reference<Command<Result<Item>>> command,
            String serial,
            Item item
        ) implements InternalSignal {}
    }
}
