package com.akamai.miniwsa.application.query;

import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import java.util.List;

/**
 * A page of matching enriched events plus the {@code total} number of matches (for
 * pagination). {@code items} are sorted by event timestamp, newest first.
 */
public record SamplePage(
        long total,
        int limit,
        int offset,
        List<EnrichedSecurityEvent> items
) {
}
