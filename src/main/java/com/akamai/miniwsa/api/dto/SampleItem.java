package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.time.Instant;

/**
 * A single enriched event in a samples response. Carries the curated field set from the
 * PRD example (identifiers, location, the matched rule's severity/category, action, and
 * the enrichment outputs).
 */
public record SampleItem(
        String eventId,
        Instant timestamp,
        long configId,
        String clientIp,
        String path,
        RuleSummary rule,
        Action action,
        String attackType,
        int threatScore,
        Instant receivedAt
) {

    public record RuleSummary(Severity severity, RuleCategory category) {
    }

    public static SampleItem from(EnrichedSecurityEvent enriched) {
        SecurityEvent event = enriched.event();
        return new SampleItem(
                event.eventId(),
                event.timestamp(),
                event.configId(),
                event.clientIp(),
                event.path(),
                new RuleSummary(event.rule().severity(), event.rule().category()),
                event.action(),
                enriched.attackType(),
                enriched.threatScore(),
                enriched.receivedAt());
    }
}
