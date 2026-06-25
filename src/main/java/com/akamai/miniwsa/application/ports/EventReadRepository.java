package com.akamai.miniwsa.application.ports;

import java.time.Instant;

/**
 * Read port used by enrichment to detect repeat offenders: how many events from the
 * same client IP already exist in a time window. The query (an IO effect) lives in the
 * application layer so the pure {@code ThreatScoreCalculator} only receives a boolean.
 */
public interface EventReadRepository {

    /**
     * Counts persisted events from {@code clientIp} with event time in
     * {@code [fromInclusive, toExclusive)}.
     */
    long countByClientIpBetween(String clientIp, Instant fromInclusive, Instant toExclusive);
}
