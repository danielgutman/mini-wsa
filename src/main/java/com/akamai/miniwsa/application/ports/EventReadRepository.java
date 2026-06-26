package com.akamai.miniwsa.application.ports;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read port used by enrichment to detect repeat offenders. Returns the event timestamps of
 * persisted events for a set of client IPs within a time window, grouped by IP — one query
 * serves a whole ingestion batch (the application does the per-event windowed counting), so
 * ingestion is not N+1 on the database. The query (an IO effect) lives in the application
 * layer so the pure {@code ThreatScoreCalculator} still only receives a boolean.
 */
public interface EventReadRepository {

    /**
     * Returns, grouped by client IP, the timestamps of persisted events whose {@code clientIp}
     * is in {@code clientIps} and whose event time is in {@code [fromInclusive, toExclusive)}.
     * IPs with no matching events may be absent from the map.
     */
    Map<String, List<Instant>> findEventTimestampsByClientIp(
            Collection<String> clientIps, Instant fromInclusive, Instant toExclusive);
}
