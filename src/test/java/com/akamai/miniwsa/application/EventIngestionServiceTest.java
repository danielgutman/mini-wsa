package com.akamai.miniwsa.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventIngestionService}: it stamps {@code receivedAt} from
 * the {@link ClockProvider} and persists via {@link EventWriteRepository}, for both
 * single and batch input. Enrichment assertions are added when that step is wired.
 */
class EventIngestionServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-20T14:32:11Z");

    private final ClockProvider fixedClock = () -> FIXED_NOW;
    private final RecordingRepository repository = new RecordingRepository();
    private final EventIngestionService service = new EventIngestionService(fixedClock, repository);

    @Test
    void ingestsSingleEventAndStampsReceivedAt() {
        int ingested = service.ingest(List.of(sampleEvent("evt-1")));

        assertThat(ingested).isEqualTo(1);
        assertThat(repository.saved).hasSize(1);
        assertThat(repository.saved.getFirst().receivedAt()).isEqualTo(FIXED_NOW);
        assertThat(repository.saved.getFirst().event().eventId()).isEqualTo("evt-1");
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
}
