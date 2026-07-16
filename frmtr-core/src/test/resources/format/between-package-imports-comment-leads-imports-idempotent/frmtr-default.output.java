/*
 * Copyright notice for the transport layer example.
 * Licensed under the Example Public License, Version 2.0.
 */

package dev.example.net;

/*
 * Transport layer for the underlying communication channel.
 * Wraps a socket channel and stands in for other channel implementations.
 */

import dev.example.net.spi.ChannelFactory;

public interface TransportLayer extends ChannelFactory {
    boolean isReady();
}
