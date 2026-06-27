package com.akamai.miniwsa.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.application.alerting.AlertEvaluation;
import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.application.alerting.FiringAlert;
import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.config.LimitsProperties;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.akamai.miniwsa.infrastructure.memory.InMemoryAlertRuleRepository;
import com.akamai.miniwsa.infrastructure.memory.InMemoryEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AlertService}, driven against the real in-memory adapters: {@code define}
 * stores a rule with an id, and {@code evaluate} counts category events in
 * {@code [now - windowMinutes, now)} (from a fixed clock), firing only when the count is strictly
 * greater than the threshold.
 */
class AlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");
    private static final long CONFIG_ID = 1L;

    private final ClockProvider fixedClock = () -> NOW;
    private final InMemoryAlertRuleRepository ruleRepository = new InMemoryAlertRuleRepository();
    private final InMemoryEventRepository eventRepository =
            new InMemoryEventRepository(new LimitsProperties(10_000, 10));
    private final AlertService service = new AlertService(ruleRepository, eventRepository, fixedClock);

    @Test
    void defineAssignsIdAndStoresRule() {
        AlertRule saved = service.define(new AlertRule(null, CONFIG_ID, RuleCategory.INJECTION, 100, 5));

        assertThat(saved.id()).isNotNull();
        assertThat(ruleRepository.findAll()).containsExactly(saved);
    }

    @Test
    void firesWhenCountExceedsThresholdOverTheWindow() {
        saveInjectionEvents(6, NOW.minus(Duration.ofMinutes(1)));   // inside the 5-minute window
        service.define(new AlertRule(null, CONFIG_ID, RuleCategory.INJECTION, 5, 5));

        AlertEvaluation result = service.evaluate();

        assertThat(result.evaluatedAt()).isEqualTo(NOW);
        assertThat(result.firing()).hasSize(1);
        FiringAlert alert = result.firing().getFirst();
        assertThat(alert.actualCount()).isEqualTo(6);
        assertThat(alert.windowFrom()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(alert.windowTo()).isEqualTo(NOW);
    }

    @Test
    void doesNotFireWhenCountIsAtOrBelowThreshold() {
        saveInjectionEvents(5, NOW.minus(Duration.ofMinutes(1)));
        service.define(new AlertRule(null, CONFIG_ID, RuleCategory.INJECTION, 5, 5));  // 5 is not > 5

        assertThat(service.evaluate().firing()).isEmpty();
    }

    @Test
    void ignoresEventsOutsideTheWindow() {
        saveInjectionEvents(6, NOW.minus(Duration.ofMinutes(10)));  // older than the 5-minute window
        service.define(new AlertRule(null, CONFIG_ID, RuleCategory.INJECTION, 0, 5));  // would fire on any in-window event

        assertThat(service.evaluate().firing()).isEmpty();
    }

    private void saveInjectionEvents(int count, Instant timestamp) {
        for (int i = 0; i < count; i++) {
            Rule rule = new Rule("1", "SQLI", "SQL Injection", Severity.CRITICAL, RuleCategory.INJECTION);
            SecurityEvent event = new SecurityEvent(
                    "evt-" + i, timestamp, CONFIG_ID, "pol", "203.0.113.42", "host", "/login", "POST",
                    403, "ua", rule, Action.DENY, null, 1L, 1L);
            eventRepository.saveAll(List.of(
                    new EnrichedSecurityEvent(event, "SQL/Command Injection", 75, timestamp)));
        }
    }
}
