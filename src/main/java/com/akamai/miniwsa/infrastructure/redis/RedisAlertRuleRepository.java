package com.akamai.miniwsa.infrastructure.redis;

import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.application.ports.AlertRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed {@link AlertRuleRepository} ({@code miniwsa.alerts.storage=redis}). Rules survive
 * restarts and are shared across app instances. Each rule is stored as a JSON value in a single
 * hash, keyed by an id from an atomic counter ({@code INCR}).
 */
@Repository
@ConditionalOnProperty(name = "miniwsa.alerts.storage", havingValue = "redis")
public class RedisAlertRuleRepository implements AlertRuleRepository {

    private static final String RULES_KEY = "miniwsa:alert-rules";
    private static final String SEQUENCE_KEY = "miniwsa:alert-rules:seq";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisAlertRuleRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public AlertRule save(AlertRule rule) {
        String id = "rule-" + redis.opsForValue().increment(SEQUENCE_KEY);
        AlertRule stored = new AlertRule(
                id, rule.configId(), rule.category(), rule.threshold(), rule.windowMinutes());
        redis.opsForHash().put(RULES_KEY, id, toJson(stored));
        return stored;
    }

    @Override
    public List<AlertRule> findAll() {
        return redis.opsForHash().values(RULES_KEY).stream()
                .map(value -> fromJson((String) value))
                .toList();
    }

    private String toJson(AlertRule rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize alert rule", e);
        }
    }

    private AlertRule fromJson(String json) {
        try {
            return objectMapper.readValue(json, AlertRule.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize alert rule: " + json, e);
        }
    }
}
