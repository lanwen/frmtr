/*
 * Copyright 2021 the Routing Toolkit contributors.
 *
 * Distributed under the terms of the project license.
 */

/**
 * Immutable endpoint description used by the dispatcher.
 */
package dev.example.routing.transport;

import java.util.Objects;

class Endpoint {

    private final String host;

    private final int port;

    Endpoint(String host, int port) {
        this.host = Objects.requireNonNull(host);
        this.port = port;
    }
}
