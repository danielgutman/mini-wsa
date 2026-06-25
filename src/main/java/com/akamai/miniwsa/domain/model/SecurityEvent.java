package com.akamai.miniwsa.domain.model;

import com.akamai.miniwsa.domain.enums.Action;
import java.time.Instant;

/**
 * A raw security event (DLR) as received from a source, before enrichment.
 *
 * <p>This is a pure domain record: it carries no Spring, JSON, or validation
 * concerns. {@code timestamp} is the event time reported by the source.
 */
public record SecurityEvent(
        String eventId,
        Instant timestamp,
        long configId,
        String policyId,
        String clientIp,
        String hostname,
        String path,
        String method,
        int statusCode,
        String userAgent,
        Rule rule,
        Action action,
        GeoLocation geoLocation,
        long requestSize,
        long responseSize
) {
}
