package com.akamai.miniwsa.application.query;

import java.time.Instant;

/**
 * Parameters for the summary statistics query. {@code configId} is optional — when
 * {@code null}, the summary aggregates across all configurations. The time range is
 * half-open: {@code [from, to)}.
 */
public record SummaryQuery(Long configId, Instant from, Instant to) {
}
