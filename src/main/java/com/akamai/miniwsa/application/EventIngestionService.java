package com.akamai.miniwsa.application;

import static java.util.stream.Collectors.toSet;

import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.akamai.miniwsa.domain.service.AttackTypeClassifier;
import com.akamai.miniwsa.domain.service.ThreatScoreCalculator;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates ingestion and enrichment of security events: stamps a server-side
 * {@code receivedAt}, classifies the attack type, computes the threat score (including the
 * repeat-offender signal), then persists. Accepts both single and batch input.
 *
 * <p>Repeat-offender history is loaded with a <b>single</b> query per batch (not one per
 * event), then the per-event 10-minute windowed count is done in pure code — so a large
 * batch is not N+1 on the database. The query is the only IO; the pure
 * {@link ThreatScoreCalculator} just receives a boolean (IO policy §5).
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
        Map<String, List<Instant>> historyByIp = loadRepeatOffenderHistory(events);

        List<EnrichedSecurityEvent> enriched = events.stream()
                .map(event -> enrich(event, receivedAt, historyByIp))
                .toList();

        writeRepository.saveAll(enriched);
        log.debug("Ingested and enriched {} event(s) at {}", enriched.size(), receivedAt);
        return enriched.size();
    }

    /**
     * Loads, in one query, the prior-event timestamps for every client IP in the batch over
     * the union of the per-event repeat-offender windows ({@code [minTimestamp - 10min,
     * maxTimestamp)}). Within-batch events are not included — they are not persisted yet.
     */
    private Map<String, List<Instant>> loadRepeatOffenderHistory(List<SecurityEvent> events) {
        if (events.isEmpty()) {
            return Map.of();
        }
        Set<String> clientIps = events.stream().map(SecurityEvent::clientIp).collect(toSet());
        Instant maxTimestamp = events.stream().map(SecurityEvent::timestamp).max(Comparator.naturalOrder()).orElseThrow();
        Instant windowStart = events.stream().map(SecurityEvent::timestamp).min(Comparator.naturalOrder())
                .orElseThrow().minus(REPEAT_OFFENDER_WINDOW);
        return readRepository.findEventTimestampsByClientIp(clientIps, windowStart, maxTimestamp);
    }

    private EnrichedSecurityEvent enrich(SecurityEvent event, Instant receivedAt,
                                         Map<String, List<Instant>> historyByIp) {
        String attackType = attackTypeClassifier.classify(event.rule().category());
        boolean repeatOffender = isRepeatOffender(event, historyByIp);
        int threatScore = threatScoreCalculator.calculate(
                event.rule().severity(), event.action(), event.path(), repeatOffender);
        return new EnrichedSecurityEvent(event, attackType, threatScore, receivedAt);
    }

    /**
     * Repeat-offender check against the pre-loaded history: counts persisted events from the
     * same IP in this event's window {@code [timestamp - 10min, timestamp)}. Pure — no IO.
     */
    private boolean isRepeatOffender(SecurityEvent event, Map<String, List<Instant>> historyByIp) {
        Instant to = event.timestamp();
        Instant from = to.minus(REPEAT_OFFENDER_WINDOW);
        long priorCount = historyByIp.getOrDefault(event.clientIp(), List.of()).stream()
                .filter(timestamp -> !timestamp.isBefore(from) && timestamp.isBefore(to))
                .count();
        return priorCount > REPEAT_OFFENDER_THRESHOLD;
    }
}
