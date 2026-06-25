package com.akamai.miniwsa.application;

import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates ingestion of security events: stamps a server-side
 * {@code receivedAt}, then persists. Accepts both single and batch input (the
 * controller passes a list either way).
 *
 * <p>Enrichment (attack-type classification and threat scoring) is wired in as a
 * separate milestone; at this stage {@code attackType}/{@code threatScore} are
 * left unset so the ingestion path can be validated on its own.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final ClockProvider clock;
    private final EventWriteRepository writeRepository;

    public EventIngestionService(ClockProvider clock, EventWriteRepository writeRepository) {
        this.clock = clock;
        this.writeRepository = writeRepository;
    }

    /**
     * Ingests a batch of events, returning the number accepted.
     *
     * @param events validated domain events (never {@code null})
     * @return count of events persisted
     */
    public int ingest(List<SecurityEvent> events) {
        Instant receivedAt = clock.now();

        List<EnrichedSecurityEvent> enriched = events.stream()
                .map(event -> new EnrichedSecurityEvent(event, null, 0, receivedAt))
                .toList();

        writeRepository.saveAll(enriched);
        log.debug("Ingested {} event(s) at {}", enriched.size(), receivedAt);
        return enriched.size();
    }
}
