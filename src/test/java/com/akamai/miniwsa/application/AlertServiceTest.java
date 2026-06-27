package com.akamai.miniwsa.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.application.alerting.AlertEvaluation;
import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.application.alerting.FiringAlert;
import com.akamai.miniwsa.application.ports.AlertRuleRepository;
import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventQueryRepository;
import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.application.query.SamplesQuery;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.TimeSeriesBucket;
import com.akamai.miniwsa.application.query.TimeSeriesQuery;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AlertService}: {@code define} stores a rule with an id, and {@code evaluate}
 * counts category events in {@code [now - windowMinutes, now)} (from a fixed clock) and fires only
 * when the count is strictly greater than the threshold.
 */
class AlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");

    private final ClockProvider fixedClock = () -> NOW;
    private final FakeRuleRepository ruleRepository = new FakeRuleRepository();
    private final FakeQueryRepository queryRepository = new FakeQueryRepository();
    private final AlertService service = new AlertService(ruleRepository, queryRepository, fixedClock);

    @Test
    void defineAssignsIdAndStoresRule() {
        AlertRule saved = service.define(new AlertRule(null, 1L, RuleCategory.INJECTION, 100, 5));

        assertThat(saved.id()).isNotNull();
        assertThat(ruleRepository.findAll()).containsExactly(saved);
    }

    @Test
    void firesWhenCountExceedsThresholdOverTheWindow() {
        service.define(new AlertRule(null, 1L, RuleCategory.INJECTION, 100, 5));
        queryRepository.count = 143;

        AlertEvaluation result = service.evaluate();

        assertThat(result.evaluatedAt()).isEqualTo(NOW);
        assertThat(result.firing()).hasSize(1);
        FiringAlert alert = result.firing().getFirst();
        assertThat(alert.actualCount()).isEqualTo(143);
        assertThat(alert.windowFrom()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(alert.windowTo()).isEqualTo(NOW);

        // It queried the rule's category over [now - 5min, now).
        assertThat(queryRepository.lastCategory).isEqualTo(RuleCategory.INJECTION);
        assertThat(queryRepository.lastFrom).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(queryRepository.lastTo).isEqualTo(NOW);
    }

    @Test
    void doesNotFireWhenCountIsAtOrBelowThreshold() {
        service.define(new AlertRule(null, 1L, RuleCategory.BOT, 100, 10));
        queryRepository.count = 100; // strictly-greater rule: 100 is not > 100

        assertThat(service.evaluate().firing()).isEmpty();
    }

    private static final class FakeRuleRepository implements AlertRuleRepository {
        private final List<AlertRule> rules = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public AlertRule save(AlertRule rule) {
            AlertRule stored = new AlertRule("rule-" + sequence.incrementAndGet(),
                    rule.configId(), rule.category(), rule.threshold(), rule.windowMinutes());
            rules.add(stored);
            return stored;
        }

        @Override
        public List<AlertRule> findAll() {
            return List.copyOf(rules);
        }
    }

    /** Fake query port: records the last count arguments and returns a canned count. */
    private static final class FakeQueryRepository implements EventQueryRepository {
        private long count;
        private RuleCategory lastCategory;
        private Instant lastFrom;
        private Instant lastTo;

        @Override
        public long countByCategory(Long configId, RuleCategory category, Instant fromInclusive, Instant toExclusive) {
            this.lastCategory = category;
            this.lastFrom = fromInclusive;
            this.lastTo = toExclusive;
            return count;
        }

        @Override
        public SummaryStats getSummary(SummaryQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SamplePage getSamples(SamplesQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TimeSeriesBucket> getTimeSeries(TimeSeriesQuery query) {
            throw new UnsupportedOperationException();
        }
    }
}
