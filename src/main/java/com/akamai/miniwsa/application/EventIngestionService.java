package com.akamai.miniwsa.application;

import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.akamai.miniwsa.domain.service.AttackTypeClassifier;
import com.akamai.miniwsa.domain.service.ThreatScoreCalculator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates ingestion and enrichment of security events: stamps a server-side
 * {@code receivedAt}, classifies the attack type, computes the threat score (including
 * the repeat-offender signal), then persists. Accepts both single and batch input.
 *
 * <p>This is where IO and pure logic meet: the repeat-offender count is an effect
 * (a read query), so it is performed here and passed as a boolean into the pure
 * {@link ThreatScoreCalculator} (IO policy §5).
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    /** Repeat-offender window and threshold (PRD §2): > 5 events from the same IP in 10 min. */
    private static final Duration REPEAT_OFFENDER_WINDOW = Duration.ofMinutes(10);
    private static final long REPEAT_OFFENDER_THRESHOLD = 5;

    private final ClockProvider clock;
    private final EventWriteRepository writeRepository;
    private final EventReadRepository readRepository;
    private final AttackTypeClassifier attackTypeClassifier;
    private final ThreatScoreCalculator threatScoreCalculator;

    public EventIngestionService(ClockProvider clock,
                                 EventWriteRepository writeRepository,
                                 EventReadRepository readRepository,
                                 AttackTypeClassifier attackTypeClassifier,
                                 ThreatScoreCalculator threatScoreCalculator) {
        this.clock = clock;
        this.writeRepository = writeRepository;
        this.readRepository = readRepository;
        this.attackTypeClassifier = attackTypeClassifier;
        this.threatScoreCalculator = threatScoreCalculator;
    }

    /**
     * Ingests and enriches a batch of events, returning the number accepted.
     *
     * @param events validated domain events (never {@code null})
     * @return count of events persisted
     */
    public int ingest(List<SecurityEvent> events) {
        Instant receivedAt = clock.now();

        List<EnrichedSecurityEvent> enriched = events.stream()
                .map(event -> enrich(event, receivedAt))
                .toList();

        writeRepository.saveAll(enriched);
        log.debug("Ingested and enriched {} event(s) at {}", enriched.size(), receivedAt);
        return enriched.size();
    }

    private EnrichedSecurityEvent enrich(SecurityEvent event, Instant receivedAt) {
        String attackType = attackTypeClassifier.classify(event.rule().category());
        boolean repeatOffender = isRepeatOffender(event);
        int threatScore = threatScoreCalculator.calculate(
                event.rule().severity(), event.action(), event.path(), repeatOffender);
        return new EnrichedSecurityEvent(event, attackType, threatScore, receivedAt);
    }

    /**
     * Repeat-offender check against already-persisted history in the window
     * {@code [timestamp - 10min, timestamp)}. Events in the same in-flight batch are not
     * counted (they are not persisted yet) — a documented simplification.
     */
    private boolean isRepeatOffender(SecurityEvent event) {
        Instant to = event.timestamp();
        Instant from = to.minus(REPEAT_OFFENDER_WINDOW);
        long priorCount = readRepository.countByClientIpBetween(event.clientIp(), from, to);
        return priorCount > REPEAT_OFFENDER_THRESHOLD;
    }
}
