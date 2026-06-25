package com.akamai.miniwsa.application.query;

import java.time.Instant;

/**
 * Parameters for the time-series query: event counts bucketed by {@link Interval} over the
 * half-open range {@code [from, to)}. {@code configId} is optional (null aggregates across
 * all configurations).
 */
public record TimeSeriesQuery(Long configId, Instant from, Instant to, Interval interval) {
}
