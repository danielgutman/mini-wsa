package com.akamai.miniwsa.application.query;

import java.util.List;
import java.util.Map;

/**
 * Aggregated statistics for a time range (and optional config): totals, per-category
 * and per-action breakdowns, and the top attackers/paths by event count.
 */
public record SummaryStats(
        long totalEvents,
        Map<String, CategoryStats> byCategory,
        Map<String, Long> byAction,
        List<AttackerStats> topAttackers,
        List<PathStats> topTargetedPaths
) {

    public record CategoryStats(long count, double avgThreatScore) {
    }

    public record AttackerStats(String clientIp, long count, double avgThreatScore) {
    }

    public record PathStats(String path, long count) {
    }
}
