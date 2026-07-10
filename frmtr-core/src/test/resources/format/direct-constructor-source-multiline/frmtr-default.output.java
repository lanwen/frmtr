class DirectConstructorSourceMultiline {

    Object route(Packet packet) {
        return switch (packet.kind()) {
            case FIRST -> {
                yield new Signal.Command.Accept(packet.id(), packet.subject(), packet.owner(), packet.replyTo());
            }
            case SECOND -> {
                yield new Signal.Command.Reject(packet.id(), packet.reason(), packet.replyTo());
            }
        };
    }

    Object create(Packet packet) {
        return new Signal.Command.Accept(packet.id(), packet.subject(), packet.owner(), packet.replyTo());
    }

    Object assign(Packet packet) {
        var accepted = new Signal.Command.Accept(packet.id(), packet.subject(), packet.owner(), packet.replyTo());
        var rejected = new Signal.Command.Reject(packet.id(), packet.reason(), packet.replyTo());
        return accepted;
    }

    Object compact(Packet packet) {
        return new Signal.Command.Accept(packet.id(), packet.subject(), packet.owner(), packet.replyTo());
    }
}
