package com.akamai.miniwsa.application.query;

import java.time.Instant;

/**
 * One time bucket: the half-open range {@code [from, to)} and the number of events in it.
 * Buckets are contiguous and aligned to the interval grid (empty buckets have count 0), so
 * the series is ready to plot as a line chart.
 */
public record TimeSeriesBucket(Instant from, Instant to, long count) {
}
