package com.akamai.miniwsa.infrastructure.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.application.query.Interval;
import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.application.query.SamplesQuery;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.TimeSeriesBucket;
import com.akamai.miniwsa.application.query.TimeSeriesQuery;
import com.akamai.miniwsa.config.LimitsProperties;
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
        repository = new ClickHouseEventRepository(jdbcTemplate, new LimitsProperties(10_000, 10));
    }

    @Test
    void persistsEnrichedEventsAndMapsAllColumns() {
        EnrichedSecurityEvent injection = enriched("evt-1", "203.0.113.42", "/api/v1/login",
                Severity.CRITICAL, RuleCategory.INJECTION, Action.DENY, "SQL/Command Injection", 90);
        EnrichedSecurityEvent bot = enriched("evt-2", "10.0.0.1", "/admin",
                Severity.HIGH, RuleCategory.BOT, Action.ALERT, "Bot Activity", 55);

        repository.saveAll(List.of(injection, bot));

        // Scope the count to this test's own events — the container is shared across tests.
        Long total = jdbcTemplate.queryForObject(
                "SELECT count() FROM security_events WHERE event_id IN ('evt-1', 'evt-2')", Long.class);
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

    @Test
    void getSamplesPaginatesNewestFirstAndReconstructsEvent() {
        long configId = 556L;
        repository.saveAll(List.of(
                summaryEvent("p1", "1.1.1.1", "/login", RuleCategory.INJECTION, Action.DENY, 75, configId, "2026-05-20T10:00:00Z"),
                summaryEvent("p2", "1.1.1.1", "/login", RuleCategory.INJECTION, Action.DENY, 75, configId, "2026-05-20T10:05:00Z"),
                summaryEvent("p3", "2.2.2.2", "/x", RuleCategory.BOT, Action.ALERT, 55, configId, "2026-05-20T10:10:00Z")));

        SamplePage page = repository.getSamples(
                new SamplesQuery(configId, null, null, null, null, 2, 0));

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(2);
        // Newest first
        assertThat(page.items().getFirst().event().eventId()).isEqualTo("p3");
        // Round-trips enum/enrichment fields out of ClickHouse
        assertThat(page.items().getFirst().event().rule().category()).isEqualTo(RuleCategory.BOT);
        assertThat(page.items().getFirst().event().action()).isEqualTo(Action.ALERT);
        assertThat(page.items().getFirst().threatScore()).isEqualTo(55);

        SamplePage filtered = repository.getSamples(
                new SamplesQuery(configId, null, null, RuleCategory.INJECTION, null, 20, 0));
        assertThat(filtered.total()).isEqualTo(2);
        assertThat(filtered.items()).allSatisfy(e ->
                assertThat(e.event().rule().category()).isEqualTo(RuleCategory.INJECTION));
    }

    @Test
    void getTimeSeriesBucketsCountsByIntervalWithGapsFilled() {
        long configId = 557L;
        // Two events in the first minute, one in the third minute; second minute is empty.
        repository.saveAll(List.of(
                summaryEvent("ts-1", "1.1.1.1", "/x", RuleCategory.BOT, Action.MONITOR, 10, configId, "2026-05-20T10:00:10Z"),
                summaryEvent("ts-2", "1.1.1.1", "/x", RuleCategory.BOT, Action.MONITOR, 10, configId, "2026-05-20T10:00:40Z"),
                summaryEvent("ts-3", "2.2.2.2", "/x", RuleCategory.BOT, Action.MONITOR, 10, configId, "2026-05-20T10:02:30Z")));

        List<TimeSeriesBucket> buckets = repository.getTimeSeries(new TimeSeriesQuery(
                configId, Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T10:03:00Z"), Interval.ONE_MINUTE));

        assertThat(buckets).hasSize(3);
        assertThat(buckets.get(0).from()).isEqualTo(Instant.parse("2026-05-20T10:00:00Z"));
        assertThat(buckets.get(0).count()).isEqualTo(2);
        assertThat(buckets.get(1).count()).isZero();  // empty bucket filled
        assertThat(buckets.get(2).count()).isEqualTo(1);
    }

    @Test
    void getTimeSeriesClampsLastBucketToRequestedTo() {
        long configId = 559L;
        repository.saveAll(List.of(
                summaryEvent("cl-1", "1.1.1.1", "/x", RuleCategory.BOT, Action.MONITOR, 10, configId, "2026-05-20T10:06:00Z")));

        // 10:00..10:07 with 5m buckets → [10:00,10:05) and [10:05,10:07); last `to` clamped to 10:07.
        List<TimeSeriesBucket> buckets = repository.getTimeSeries(new TimeSeriesQuery(
                configId, Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T10:07:00Z"), Interval.FIVE_MINUTES));

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(1).from()).isEqualTo(Instant.parse("2026-05-20T10:05:00Z"));
        assertThat(buckets.get(1).to()).isEqualTo(Instant.parse("2026-05-20T10:07:00Z"));
        assertThat(buckets.get(1).count()).isEqualTo(1);
    }

    @Test
    void countsEventsByCategoryInWindow() {
        long configId = 560L;
        repository.saveAll(List.of(
                summaryEvent("cc-1", "1.1.1.1", "/x", RuleCategory.INJECTION, Action.DENY, 90, configId, "2026-05-20T10:00:00Z"),
                summaryEvent("cc-2", "1.1.1.1", "/x", RuleCategory.INJECTION, Action.DENY, 90, configId, "2026-05-20T10:05:00Z"),
                summaryEvent("cc-3", "2.2.2.2", "/x", RuleCategory.BOT, Action.ALERT, 55, configId, "2026-05-20T10:06:00Z")));

        Instant dayStart = Instant.parse("2026-05-20T00:00:00Z");
        Instant dayEnd = Instant.parse("2026-05-21T00:00:00Z");

        // category filter: 2 INJECTION, 1 BOT
        assertThat(repository.countByCategory(configId, RuleCategory.INJECTION, dayStart, dayEnd)).isEqualTo(2);
        assertThat(repository.countByCategory(configId, RuleCategory.BOT, dayStart, dayEnd)).isEqualTo(1);

        // window is half-open [from, to): from 10:01 excludes cc-1 (10:00), keeps cc-2 (10:05)
        assertThat(repository.countByCategory(
                configId, RuleCategory.INJECTION, Instant.parse("2026-05-20T10:01:00Z"), dayEnd)).isEqualTo(1);
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
