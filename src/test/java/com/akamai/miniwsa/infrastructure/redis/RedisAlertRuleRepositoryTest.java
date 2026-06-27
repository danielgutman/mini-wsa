package com.akamai.miniwsa.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link RedisAlertRuleRepository} against a real Redis in a container.
 * {@code disabledWithoutDocker} so it is skipped without Docker and runs in CI. Exercises the
 * adapter directly (no Spring context) over a {@link StringRedisTemplate} bound to the container.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisAlertRuleRepositoryTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static RedisAlertRuleRepository repository;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();

        StringRedisSerializer keys = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<AlertRule> values =
                new Jackson2JsonRedisSerializer<>(new ObjectMapper(), AlertRule.class);
        RedisTemplate<String, AlertRule> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(keys);
        template.setHashKeySerializer(keys);
        template.setValueSerializer(values);
        template.setHashValueSerializer(values);
        template.afterPropertiesSet();

        repository = new RedisAlertRuleRepository(template);
    }

    @Test
    void savesAssignsIdsAndRoundTripsRules() {
        AlertRule scoped = repository.save(new AlertRule(null, 14227L, RuleCategory.INJECTION, 100, 5));
        AlertRule allConfigs = repository.save(new AlertRule(null, null, RuleCategory.BOT, 50, 10));

        // ids are assigned and distinct
        assertThat(scoped.id()).isNotNull();
        assertThat(allConfigs.id()).isNotNull().isNotEqualTo(scoped.id());

        // both round-trip out of Redis with all fields intact (including a null configId)
        List<AlertRule> all = repository.findAll();
        assertThat(all).containsExactlyInAnyOrder(scoped, allConfigs);
        assertThat(all).anySatisfy(rule -> {
            assertThat(rule.category()).isEqualTo(RuleCategory.BOT);
            assertThat(rule.configId()).isNull();
            assertThat(rule.threshold()).isEqualTo(50);
            assertThat(rule.windowMinutes()).isEqualTo(10);
        });
    }
}
