package com.akamai.miniwsa.application;

import static java.util.stream.Collectors.toSet;

import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.config.tunables.Tunables;
import com.akamai.miniwsa.config.tunables.TunablesHolder;
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
 * <p>The repeat-offender window/threshold and the scoring weights are read from
 * {@link TunablesHolder} as a single snapshot per batch, so a live configuration change applies
 * to the next batch without a restart while keeping each batch internally consistent. The query
 * is the only IO; the pure {@link ThreatScoreCalculator} just receives the weights and a boolean.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final ClockProvider clock;
    private final EventWriteRepository writeRepository;
    private final EventReadRepository readRepository;
    private final AttackTypeClassifier attackTypeClassifier;
    private final ThreatScoreCalculator threatScoreCalculator;
    private final TunablesHolder tunables;

    public EventIngestionService(ClockProvider clock,
                                 EventWriteRepository writeRepository,
                                 EventReadRepository readRepository,
                                 AttackTypeClassifier attackTypeClassifier,
                                 ThreatScoreCalculator threatScoreCalculator,
                                 TunablesHolder tunables) {
        this.clock = clock;
        this.writeRepository = writeRepository;
        this.readRepository = readRepository;
        this.attackTypeClassifier = attackTypeClassifier;
        this.threatScoreCalculator = threatScoreCalculator;
        this.tunables = tunables;
    }

    /**
     * Ingests and enriches a batch of events, returning the number accepted.
     *
     * @param events validated domain events (never {@code null})
     * @return count of events persisted
     */
    public int ingest(List<SecurityEvent> events) {
        Instant receivedAt = clock.now();
        Tunables snapshot = tunables.current();
        Map<String, List<Instant>> historyByIp = loadRepeatOffenderHistory(events, snapshot.repeatOffenderWindow());

        List<EnrichedSecurityEvent> enriched = events.stream()
                .map(event -> enrich(event, receivedAt, historyByIp, snapshot))
                .toList();

        writeRepository.saveAll(enriched);
        log.debug("Ingested and enriched {} event(s) at {}", enriched.size(), receivedAt);
        return enriched.size();
    }

    /**
     * Loads, in one query, the prior-event timestamps for every client IP in the batch over the
     * union of the per-event repeat-offender windows ({@code [minTimestamp - window, maxTimestamp)}).
     * Within-batch events are not included — they are not persisted yet.
     */
    private Map<String, List<Instant>> loadRepeatOffenderHistory(List<SecurityEvent> events, Duration window) {
        if (events.isEmpty()) {
            return Map.of();
        }
        Set<String> clientIps = events.stream().map(SecurityEvent::clientIp).collect(toSet());
        Instant maxTimestamp = events.stream().map(SecurityEvent::timestamp).max(Comparator.naturalOrder()).orElseThrow();
        Instant windowStart = events.stream().map(SecurityEvent::timestamp).min(Comparator.naturalOrder())
                .orElseThrow().minus(window);
        return readRepository.findEventTimestampsByClientIp(clientIps, windowStart, maxTimestamp);
    }

    private EnrichedSecurityEvent enrich(SecurityEvent event, Instant receivedAt,
                                         Map<String, List<Instant>> historyByIp, Tunables snapshot) {
        String attackType = attackTypeClassifier.classify(event.rule().category());
        boolean repeatOffender = isRepeatOffender(event, historyByIp, snapshot);
        int threatScore = threatScoreCalculator.calculate(
                event.rule().severity(), event.action(), event.path(), repeatOffender, snapshot.scoring());
        return new EnrichedSecurityEvent(event, attackType, threatScore, receivedAt);
    }

    /**
     * Repeat-offender check against the pre-loaded history: counts persisted events from the same
     * IP in this event's window {@code [timestamp - window, timestamp)} and compares to the
     * threshold.
     *
     * <p><b>Threshold semantics — a deliberate reading of an ambiguous spec.</b> The PRD says "if
     * more than 5 events from the same clientIp exist within the last 10 minutes, add +15", which
     * does not state whether the event being scored counts toward the threshold. We count only the
     * <em>prior persisted</em> events: the window is half-open and excludes the current event's own
     * timestamp, and earlier events in the same in-flight batch are not counted (not persisted
     * yet). So with the default threshold of 5 the bonus first triggers on the 7th event from an IP
     * (6 priors {@code >} 5), not the 6th. To make the current event count, use {@code >=}.
     *
     * <p><b>Consequence — single-request bursts.</b> Because only prior persisted events count, an
     * attack wave delivered as one batch (e.g. the PRD's "50 events from the same IP against /login
     * within 3 minutes") receives no repeat-offender bonus. Waves spread across separate requests
     * over time are detected normally.
     */
    private boolean isRepeatOffender(SecurityEvent event, Map<String, List<Instant>> historyByIp, Tunables snapshot) {
        Instant to = event.timestamp();
        Instant from = to.minus(snapshot.repeatOffenderWindow());
        long priorCount = historyByIp.getOrDefault(event.clientIp(), List.of()).stream()
                .filter(timestamp -> !timestamp.isBefore(from) && timestamp.isBefore(to))
                .count();
        return priorCount > snapshot.repeatOffenderThreshold();
    }
}
