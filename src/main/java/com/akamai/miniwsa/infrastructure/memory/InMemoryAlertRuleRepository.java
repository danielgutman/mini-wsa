package com.akamai.miniwsa.infrastructure.memory;

import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.application.ports.AlertRuleRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link AlertRuleRepository}. Rules are small config-like state, so they live in memory
 * regardless of {@code miniwsa.storage}; they are not persisted across restarts (a documented
 * simplification — see the README).
 */
@Repository
public class InMemoryAlertRuleRepository implements AlertRuleRepository {

    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public AlertRule save(AlertRule rule) {
        String id = "rule-" + sequence.incrementAndGet();
        AlertRule stored = new AlertRule(
                id, rule.configId(), rule.category(), rule.threshold(), rule.windowMinutes());
        rules.put(id, stored);
        return stored;
    }

    @Override
    public List<AlertRule> findAll() {
        return List.copyOf(rules.values());
    }
}
