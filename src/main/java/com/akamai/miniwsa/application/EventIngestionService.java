package com.akamai.miniwsa.application;

import static java.util.stream.Collectors.toSet;

import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.akamai.miniwsa.domain.service.AttackTypeClassifier;
import com.akamai.miniwsa.domain.service.ThreatScoreCalculator;
import com.akamai.miniwsa.observability.IngestionMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * {@link ThreatScoreCalculator} just receives a boolean.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    /**
     * Repeat-offender window and threshold (PRD §2): "more than 5 events from the same clientIp
     * within the last 10 minutes" → +15. See {@link #isRepeatOffender} for the deliberate reading
     * of which events count toward the 5.
     */
    private static final Duration REPEAT_OFFENDER_WINDOW = Duration.ofMinutes(10);
    private static final long REPEAT_OFFENDER_THRESHOLD = 5;

    private final ClockProvider clock;
    private final EventWriteRepository writeRepository;
    private final EventReadRepository readRepository;
    private final AttackTypeClassifier attackTypeClassifier;
    private final ThreatScoreCalculator threatScoreCalculator;
    private final IngestionMetrics metrics;

    public EventIngestionService(ClockProvider clock,
                                 EventWriteRepository writeRepository,
                                 EventReadRepository readRepository,
                                 AttackTypeClassifier attackTypeClassifier,
                                 ThreatScoreCalculator threatScoreCalculator,
                                 IngestionMetrics metrics) {
        this.clock = clock;
        this.writeRepository = writeRepository;
        this.readRepository = readRepository;
        this.attackTypeClassifier = attackTypeClassifier;
        this.threatScoreCalculator = threatScoreCalculator;
        this.metrics = metrics;
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

        // Compute the repeat-offender flag once per event: it feeds both the score and the metric.
        List<EnrichedSecurityEvent> enriched = new ArrayList<>(events.size());
        long repeatOffenders = 0;
        for (SecurityEvent event : events) {
            boolean repeatOffender = isRepeatOffender(event, historyByIp);
            if (repeatOffender) {
                repeatOffenders++;
            }
            enriched.add(enrich(event, receivedAt, repeatOffender));
        }

        writeRepository.saveAll(enriched);
        metrics.recordBatch(enriched, repeatOffenders);
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

    private EnrichedSecurityEvent enrich(SecurityEvent event, Instant receivedAt, boolean repeatOffender) {
        String attackType = attackTypeClassifier.classify(event.rule().category());
        int threatScore = threatScoreCalculator.calculate(
                event.rule().severity(), event.action(), event.path(), repeatOffender);
        return new EnrichedSecurityEvent(event, attackType, threatScore, receivedAt);
    }

    /**
     * Repeat-offender check against the pre-loaded history: counts persisted events from the
     * same IP in this event's window {@code [timestamp - 10min, timestamp)}.
     *
     * <p><b>Threshold semantics — a deliberate reading of an ambiguous spec.</b> The PRD says
     * "if more than 5 events from the same clientIp exist within the last 10 minutes, add +15",
     * which does not state whether the event being scored counts toward the 5. We count only the
     * <em>prior persisted</em> events:
     * <ul>
     *   <li>the window is half-open and excludes the current event's own timestamp
     *       ({@code toExclusive == event.timestamp()}); and</li>
     *   <li>events earlier in the same in-flight batch are not counted either — they are not
     *       persisted until after enrichment.</li>
     * </ul>
     * So the bonus first triggers on the <b>7th</b> event from an IP (6 priors {@code > 5}), not
     * the 6th. Rationale: "events that exist within the last 10 minutes" reads naturally as
     * already-recorded history relative to this event, and a prior-only count is unambiguous and
     * independent of ordering within a batch. To instead make the current event count toward the
     * 5, change the comparison below to {@code priorCount >= REPEAT_OFFENDER_THRESHOLD}.
     *
     * <p><b>Consequence — single-request bursts.</b> Because only prior persisted events count, an
     * attack wave delivered as one batch (e.g. the PRD's "50 events from the same IP against
     * /login within 3 minutes") receives no repeat-offender bonus — each event sees only history
     * that existed <em>before</em> the request. Waves spread across separate requests over time
     * are detected normally. To also flag intra-batch waves, fold earlier same-batch events
     * (sorted by timestamp) into each event's window before scoring.
     */
    private boolean isRepeatOffender(SecurityEvent event, Map<String, List<Instant>> historyByIp) {
        Instant to = event.timestamp();
        Instant from = to.minus(REPEAT_OFFENDER_WINDOW);
        long priorCount = historyByIp.getOrDefault(event.clientIp(), List.of()).stream()
                .filter(timestamp -> !timestamp.isBefore(from) && timestamp.isBefore(to))
                .count();
        // Strict ">": prior events only; current/within-batch events do not count (see Javadoc).
        return priorCount > REPEAT_OFFENDER_THRESHOLD;
    }
}
