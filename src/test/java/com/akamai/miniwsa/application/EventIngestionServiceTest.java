package com.akamai.miniwsa.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.akamai.miniwsa.domain.service.AttackTypeClassifier;
import com.akamai.miniwsa.domain.service.ThreatScoreCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventIngestionService}: it stamps {@code receivedAt} from the
 * {@link ClockProvider}, enriches each event (attack type + threat score, using the
 * repeat-offender count from {@link EventReadRepository}), and persists via
 * {@link EventWriteRepository}, for both single and batch input.
 */
class EventIngestionServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-20T14:32:11Z");

    private final ClockProvider fixedClock = () -> FIXED_NOW;
    private final RecordingRepository repository = new RecordingRepository();
    private final StubReadRepository readRepository = new StubReadRepository();
    private final EventIngestionService service = new EventIngestionService(
            fixedClock, repository, readRepository, new AttackTypeClassifier(), new ThreatScoreCalculator());

    @Test
    void enrichesSingleEventWithAttackTypeScoreAndReceivedAt() {
        // CRITICAL(40) + DENY(20) + /login(15), not a repeat offender = 75
        int ingested = service.ingest(List.of(sampleEvent("evt-1")));

        assertThat(ingested).isEqualTo(1);
        EnrichedSecurityEvent saved = repository.saved.getFirst();
        assertThat(saved.receivedAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.attackType()).isEqualTo("SQL/Command Injection");
        assertThat(saved.threatScore()).isEqualTo(75);
    }

    @Test
    void appliesRepeatOffenderBonusWhenHistoryExceedsThreshold() {
        readRepository.count = 6; // > 5 within the window

        service.ingest(List.of(sampleEvent("evt-1")));

        // 75 baseline + repeat-offender 15 = 90
        assertThat(repository.saved.getFirst().threatScore()).isEqualTo(90);
    }

    @Test
    void ingestsBatchAndReturnsCount() {
        int ingested = service.ingest(List.of(sampleEvent("evt-1"), sampleEvent("evt-2"), sampleEvent("evt-3")));

        assertThat(ingested).isEqualTo(3);
        assertThat(repository.saved).extracting(e -> e.event().eventId())
                .containsExactly("evt-1", "evt-2", "evt-3");
    }

    @Test
    void emptyBatchPersistsNothing() {
        int ingested = service.ingest(List.of());

        assertThat(ingested).isZero();
        assertThat(repository.saved).isEmpty();
    }

    private static SecurityEvent sampleEvent(String id) {
        Rule rule = new Rule("950001", "SQL_INJECTION", "SQL Injection", Severity.CRITICAL, RuleCategory.INJECTION);
        return new SecurityEvent(
                id,
                Instant.parse("2026-05-20T14:32:10Z"),
                14227L,
                "pol_web1",
                "203.0.113.42",
                "www.example.com",
                "/api/v1/login",
                "POST",
                403,
                "Mozilla/5.0",
                rule,
                Action.DENY,
                null,
                1024L,
                256L
        );
    }

    /** Minimal fake capturing what was saved. */
    private static final class RecordingRepository implements EventWriteRepository {
        private final List<EnrichedSecurityEvent> saved = new ArrayList<>();

        @Override
        public void saveAll(List<EnrichedSecurityEvent> events) {
            saved.addAll(events);
        }
    }

    /** Fake read repository returning a configurable repeat-offender count. */
    private static final class StubReadRepository implements EventReadRepository {
        private long count = 0;

        @Override
        public long countByClientIpBetween(String clientIp, Instant fromInclusive, Instant toExclusive) {
            return count;
        }
    }
}
