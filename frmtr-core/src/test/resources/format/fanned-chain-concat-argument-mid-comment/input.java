class ShortMessageDispatcher {

    void sendLongBody(Exchange exchange) {
        exchange.getIn().setBody("Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP World! "
                                 + "Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP World! "); // 270 chars
    }

    void sendAnnotatedBody(Exchange exchange) {
        exchange.getIn().setBody("Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP "
                                 + // continuation carries the remaining segment
                                 "Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP World! Hello SMPP ");
    }
}
