package com.akamai.miniwsa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IngestionMetrics}: a recorded batch increments the ingest/repeat-offender
 * counters and feeds the batch-size and threat-score distributions; an empty batch records nothing.
 */
class IngestionMetricsTest {

    @Test
    void recordsCountersBatchSizeAndScoreDistribution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionMetrics metrics = new IngestionMetrics(registry);

        metrics.recordBatch(List.of(enriched(75), enriched(90)), 1);

        assertThat(registry.get("miniwsa.ingest.events").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("miniwsa.ingest.repeat_offenders").counter().count()).isEqualTo(1.0);

        DistributionSummary batchSize = registry.get("miniwsa.ingest.batch.size").summary();
        assertThat(batchSize.count()).isEqualTo(1);
        assertThat(batchSize.totalAmount()).isEqualTo(2.0);

        DistributionSummary threatScore = registry.get("miniwsa.threat.score").summary();
        assertThat(threatScore.count()).isEqualTo(2);
        assertThat(threatScore.totalAmount()).isEqualTo(165.0);
        assertThat(threatScore.max()).isEqualTo(90.0);
    }

    @Test
    void emptyBatchRecordsNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionMetrics metrics = new IngestionMetrics(registry);

        metrics.recordBatch(List.of(), 0);

        assertThat(registry.get("miniwsa.ingest.events").counter().count()).isZero();
        assertThat(registry.get("miniwsa.ingest.batch.size").summary().count()).isZero();
    }

    private static EnrichedSecurityEvent enriched(int threatScore) {
        Rule rule = new Rule("r", "R", "m", Severity.HIGH, RuleCategory.BOT);
        SecurityEvent event = new SecurityEvent(
                "id", Instant.parse("2026-05-20T10:00:00Z"), 1L, "pol", "1.1.1.1", "host", "/x",
                "GET", 200, "ua", rule, Action.MONITOR, null, 1L, 1L);
        return new EnrichedSecurityEvent(event, "Bot Activity", threatScore, Instant.parse("2026-05-20T10:00:01Z"));
    }
}
