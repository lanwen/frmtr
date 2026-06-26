/*
 * Licensed to the Example Foundation under one or more
 * contributor license agreements. See the project NOTICE file.
 */
package dev.example.routing.dispatch;

import java.util.Map;

/**
 * @since 4.2
 */
// note: made public in 4.2 to be shared across tiers

@Deprecated
public abstract class RetryLedger {

    Map<String, Integer> attempts;
}
