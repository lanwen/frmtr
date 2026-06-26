/*
 * Licensed to the Example Foundation under one or more
 * contributor license agreements. See the project NOTICE file.
 */
package dev.example.routing.dispatch;

import java.util.List;

/**
 * Coordinates dispatch retries across the routing tier.
 *
 * @since 4.2
 */
public abstract class DispatchCoordinator {

    List<String> pendingRoutes;
}
