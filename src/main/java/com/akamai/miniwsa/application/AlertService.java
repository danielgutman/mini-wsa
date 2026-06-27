package com.akamai.miniwsa.application;

import com.akamai.miniwsa.application.alerting.AlertEvaluation;
import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.application.alerting.FiringAlert;
import com.akamai.miniwsa.application.ports.AlertRuleRepository;
import com.akamai.miniwsa.application.ports.ClockProvider;
import com.akamai.miniwsa.application.ports.EventQueryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Defines alert rules and evaluates them against the stored events (the alerting bonus).
 *
 * <p>{@code evaluate} reads "now" from the {@link ClockProvider} (so it is deterministic and
 * unit-testable with a fixed clock) and, for each rule, counts events of its category in the
 * window {@code [now - windowMinutes, now)} via the {@link EventQueryRepository}. A rule fires
 * when that count is strictly greater than its threshold.
 */
@Service
public class AlertService {

    private final AlertRuleRepository ruleRepository;
    private final EventQueryRepository queryRepository;
    private final ClockProvider clock;

    public AlertService(AlertRuleRepository ruleRepository,
                        EventQueryRepository queryRepository,
                        ClockProvider clock) {
        this.ruleRepository = ruleRepository;
        this.queryRepository = queryRepository;
        this.clock = clock;
    }

    /** Stores a new rule and returns it with its assigned id. */
    public AlertRule define(AlertRule rule) {
        return ruleRepository.save(rule);
    }

    /** Evaluates every defined rule against the current data and returns the ones firing now. */
    public AlertEvaluation evaluate() {
        Instant now = clock.now();
        List<FiringAlert> firing = new ArrayList<>();
        for (AlertRule rule : ruleRepository.findAll()) {
            Instant from = now.minus(Duration.ofMinutes(rule.windowMinutes()));
            long count = queryRepository.countByCategory(rule.configId(), rule.category(), from, now);
            if (count > rule.threshold()) {
                firing.add(new FiringAlert(rule, count, from, now));
            }
        }
        return new AlertEvaluation(now, firing);
    }
}
