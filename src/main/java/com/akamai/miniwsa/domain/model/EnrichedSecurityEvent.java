package com.akamai.miniwsa.domain.model;

import java.time.Instant;

/**
 * A {@link SecurityEvent} after the enrichment step, carrying the original event
 * plus the three derived fields the pipeline computes:
 *
 * <ul>
 *   <li>{@code attackType}  — human-readable category (from the classifier).</li>
 *   <li>{@code threatScore} — integer 0..100 (from the threat-score calculator).</li>
 *   <li>{@code receivedAt}  — server-side ingestion time.</li>
 * </ul>
 */
public record EnrichedSecurityEvent(
        SecurityEvent event,
        String attackType,
        int threatScore,
        Instant receivedAt
) {
}
