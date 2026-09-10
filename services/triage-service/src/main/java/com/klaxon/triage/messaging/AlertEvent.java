package com.klaxon.triage.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire format on alerts.raw. A record, versioned by a header rather than a
 * registry: one producer, one consumer, no compatibility problem to solve yet.
 */
public record AlertEvent(
        UUID eventId,
        UUID alertId,
        String source,
        String service,
        String host,
        String title,
        String body,
        String severity,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "1";
}
