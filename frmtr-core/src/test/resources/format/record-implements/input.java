class RecordImplementsSample {
    interface InternalSignal {
        record CompletedSignal(Reference<Result<Item>> sink, @Nullable Marker marker, Item item) implements
            InternalSignal {}

        record AcceptedSignal(Reference<Command<Result<Item>>> command, String serial, Item item) implements
            InternalSignal {}

        record UpdateEntry(String id, String item, String state, Map<String, String> values) implements InternalSignal {}
    }
}
