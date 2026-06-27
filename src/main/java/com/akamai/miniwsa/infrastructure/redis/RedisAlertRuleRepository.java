package com.akamai.miniwsa.infrastructure.redis;

import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.application.ports.AlertRuleRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed {@link AlertRuleRepository} ({@code miniwsa.alerts.storage=redis}). Rules survive
 * restarts and are shared across app instances. Each rule is a JSON value in a single hash, keyed
 * by an id from an atomic counter ({@code INCR}). The {@link RedisTemplate} (see {@link RedisConfig})
 * does the JSON (de)serialization, so this adapter holds no error handling of its own.
 */
@Repository
@ConditionalOnProperty(name = "miniwsa.alerts.storage", havingValue = "redis")
public class RedisAlertRuleRepository implements AlertRuleRepository {

    private static final String RULES_KEY = "miniwsa:alert-rules";
    private static final String SEQUENCE_KEY = "miniwsa:alert-rules:seq";

    private final RedisTemplate<String, AlertRule> redis;

    public RedisAlertRuleRepository(RedisTemplate<String, AlertRule> redis) {
        this.redis = redis;
    }

    @Override
    public AlertRule save(AlertRule rule) {
        String id = "rule-" + redis.opsForValue().increment(SEQUENCE_KEY);
        AlertRule stored = new AlertRule(
                id, rule.configId(), rule.category(), rule.threshold(), rule.windowMinutes());
        redis.<String, AlertRule>opsForHash().put(RULES_KEY, id, stored);
        return stored;
    }

    @Override
    public List<AlertRule> findAll() {
        return redis.<String, AlertRule>opsForHash().values(RULES_KEY).stream().toList();
    }
}
