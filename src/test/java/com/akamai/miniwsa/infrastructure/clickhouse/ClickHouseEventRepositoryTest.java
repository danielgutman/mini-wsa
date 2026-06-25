package com.akamai.miniwsa.infrastructure.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.GeoLocation;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link ClickHouseEventRepository} against a real ClickHouse in a
 * container. Annotated {@code disabledWithoutDocker} so it is skipped on machines without
 * Docker and runs in CI. Exercises the adapter directly (no Spring context) over a
 * JdbcTemplate bound to the container.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClickHouseEventRepositoryTest {

    @Container
    static ClickHouseContainer clickhouse =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8"))
                    .withUsername("mini_wsa")
                    .withPassword("mini_wsa")
                    .withDatabaseName("mini_wsa");

    private static JdbcTemplate jdbcTemplate;
    private static ClickHouseEventRepository repository;

    @BeforeAll
    static void setUp() throws IOException {
        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName(clickhouse.getDriverClassName())
                .url(clickhouse.getJdbcUrl())
                .username(clickhouse.getUsername())
                .password(clickhouse.getPassword())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(readSchema());
        repository = new ClickHouseEventRepository(jdbcTemplate);
    }

    @Test
    void persistsEnrichedEventsAndMapsAllColumns() {
        EnrichedSecurityEvent injection = enriched("evt-1", "203.0.113.42", "/api/v1/login",
                Severity.CRITICAL, RuleCategory.INJECTION, Action.DENY, "SQL/Command Injection", 90);
        EnrichedSecurityEvent bot = enriched("evt-2", "10.0.0.1", "/admin",
                Severity.HIGH, RuleCategory.BOT, Action.ALERT, "Bot Activity", 55);

        repository.saveAll(List.of(injection, bot));

        Long total = jdbcTemplate.queryForObject("SELECT count() FROM security_events", Long.class);
        assertThat(total).isEqualTo(2L);

        var row = jdbcTemplate.queryForMap(
                "SELECT client_ip, rule_category, action, attack_type, threat_score "
                        + "FROM security_events WHERE event_id = 'evt-1'");
        assertThat(row.get("client_ip")).isEqualTo("203.0.113.42");
        assertThat(row.get("rule_category")).isEqualTo("INJECTION");
        assertThat(row.get("action")).isEqualTo("DENY");
        assertThat(row.get("attack_type")).isEqualTo("SQL/Command Injection");
        assertThat(((Number) row.get("threat_score")).intValue()).isEqualTo(90);
    }

    @Test
    void getSummaryAggregatesByConfig() {
        // Use a dedicated configId so this test is isolated from other tests' rows in the
        // shared container, then filter the summary by that config.
        long configId = 555L;
        repository.saveAll(List.of(
                summaryEvent("sum-1", "1.1.1.1", "/login", RuleCategory.INJECTION, Action.DENY, 90, configId, "2026-05-20T10:00:00Z"),
                summaryEvent("sum-2", "1.1.1.1", "/login", RuleCategory.INJECTION, Action.DENY, 90, configId, "2026-05-20T10:01:00Z"),
                summaryEvent("sum-3", "2.2.2.2", "/x", RuleCategory.BOT, Action.ALERT, 55, configId, "2026-05-20T10:02:00Z")));

        SummaryStats stats = repository.getSummary(new SummaryQuery(
                configId, Instant.parse("2026-05-20T00:00:00Z"), Instant.parse("2026-05-21T00:00:00Z")));

        assertThat(stats.totalEvents()).isEqualTo(3);
        assertThat(stats.byCategory().get("INJECTION").count()).isEqualTo(2);
        assertThat(stats.byCategory().get("INJECTION").avgThreatScore()).isEqualTo(90.0);
        assertThat(stats.byAction().get("DENY")).isEqualTo(2);
        assertThat(stats.byAction().get("ALERT")).isEqualTo(1);
        assertThat(stats.topAttackers().getFirst().clientIp()).isEqualTo("1.1.1.1");
        assertThat(stats.topAttackers().getFirst().count()).isEqualTo(2);
        assertThat(stats.topTargetedPaths().getFirst().path()).isEqualTo("/login");
        assertThat(stats.topTargetedPaths().getFirst().count()).isEqualTo(2);
    }

    private static EnrichedSecurityEvent summaryEvent(String id, String ip, String path, RuleCategory category,
                                                      Action action, int threatScore, long configId, String timestamp) {
        Rule rule = new Rule("r", "R", "m", Severity.HIGH, category);
        SecurityEvent event = new SecurityEvent(
                id, Instant.parse(timestamp), configId, "pol", ip, "host", path, "GET", 200, "ua",
                rule, action, new GeoLocation("US", "NY"), 10L, 20L);
        return new EnrichedSecurityEvent(event, "attack", threatScore, Instant.parse("2026-05-20T14:32:11Z"));
    }

    private static EnrichedSecurityEvent enriched(String id, String ip, String path,
                                                  Severity severity, RuleCategory category,
                                                  Action action, String attackType, int threatScore) {
        Rule rule = new Rule("950001", "RULE", "message", severity, category);
        SecurityEvent event = new SecurityEvent(
                id, Instant.parse("2026-05-20T14:32:10Z"), 14227L, "pol_web1", ip,
                "www.example.com", path, "POST", 403, "Mozilla/5.0", rule, action,
                new GeoLocation("CN", "Beijing"), 1024L, 256L);
        return new EnrichedSecurityEvent(event, attackType, threatScore, Instant.parse("2026-05-20T14:32:11Z"));
    }

    private static String readSchema() throws IOException {
        return new String(new ClassPathResource("db/ClickHouseSchema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
