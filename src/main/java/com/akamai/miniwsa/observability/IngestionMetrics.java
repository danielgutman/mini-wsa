package com.akamai.miniwsa.observability;

import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pipeline-specific ingestion metrics, registered with Micrometer and scraped at
 * {@code /actuator/prometheus}. These complement the metrics Spring Boot already exposes out of
 * the box (HTTP request rate/latency via {@code http.server.requests}, plus JVM and system meters)
 * with signals unique to this service: ingest volume, batch size, the threat-score distribution,
 * and how often events come from repeat offenders.
 */
@Component
public class IngestionMetrics {

    private final Counter eventsIngested;
    private final Counter repeatOffenders;
    private final DistributionSummary batchSize;
    private final DistributionSummary threatScore;

    public IngestionMetrics(MeterRegistry registry) {
        this.eventsIngested = Counter.builder("miniwsa.ingest.events")
                .description("Security events accepted and enriched")
                .baseUnit("events")
                .register(registry);
        this.repeatOffenders = Counter.builder("miniwsa.ingest.repeat_offenders")
                .description("Ingested events flagged as repeat offenders")
                .baseUnit("events")
                .register(registry);
        this.batchSize = DistributionSummary.builder("miniwsa.ingest.batch.size")
                .description("Events per ingest request")
                .baseUnit("events")
                .register(registry);
        this.threatScore = DistributionSummary.builder("miniwsa.threat.score")
                .description("Threat score (0-100) assigned to enriched events")
                .register(registry);
    }

    /**
     * Records one successfully persisted batch: the total ingested, the batch size, the
     * repeat-offender count, and the per-event threat-score distribution.
     *
     * @param enriched          the enriched events just persisted
     * @param repeatOffenderCount how many of them were flagged as repeat offenders
     */
    public void recordBatch(List<EnrichedSecurityEvent> enriched, long repeatOffenderCount) {
        if (enriched.isEmpty()) {
            return;
        }
        eventsIngested.increment(enriched.size());
        batchSize.record(enriched.size());
        repeatOffenders.increment(repeatOffenderCount);
        enriched.forEach(event -> threatScore.record(event.threatScore()));
    }
}
