package com.akamai.miniwsa.infrastructure.redis;

import com.akamai.miniwsa.application.alerting.AlertRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis wiring for the alert-rule store, active only when {@code miniwsa.alerts.storage=redis}.
 * A {@link RedisTemplate} with String keys and JSON-serialized {@link AlertRule} values, so the
 * repository never (de)serializes by hand — serialization failures become Spring's unchecked
 * {@code SerializationException}, handled in the one central place rather than thrown locally.
 */
@Configuration
@ConditionalOnProperty(name = "miniwsa.alerts.storage", havingValue = "redis")
public class RedisConfig {

    @Bean
    RedisTemplate<String, AlertRule> alertRuleRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        StringRedisSerializer keys = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<AlertRule> values =
                new Jackson2JsonRedisSerializer<>(objectMapper, AlertRule.class);

        RedisTemplate<String, AlertRule> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(keys);
        template.setHashKeySerializer(keys);
        template.setValueSerializer(values);
        template.setHashValueSerializer(values);
        return template;
    }
}
