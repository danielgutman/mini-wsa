package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.SummaryStats.AttackerStats;
import com.akamai.miniwsa.application.query.SummaryStats.CategoryStats;
import com.akamai.miniwsa.application.query.SummaryStats.PathStats;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code GET /v1/stats/summary}. Echoes the requested {@code configId}
 * (omitted from JSON when querying across all configs) and time range, then the
 * aggregates. Reuses the {@link SummaryStats} record types directly, whose field names
 * match the API contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummaryResponse(
        Long configId,
        TimeRange timeRange,
        long totalEvents,
        Map<String, CategoryStats> byCategory,
        Map<String, Long> byAction,
        List<AttackerStats> topAttackers,
        List<PathStats> topTargetedPaths
) {

    public record TimeRange(Instant from, Instant to) {
    }

    public static SummaryResponse from(Long configId, Instant from, Instant to, SummaryStats stats) {
        return new SummaryResponse(
                configId,
                new TimeRange(from, to),
                stats.totalEvents(),
                stats.byCategory(),
                stats.byAction(),
                stats.topAttackers(),
                stats.topTargetedPaths());
    }
}
