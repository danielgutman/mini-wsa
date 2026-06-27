package com.akamai.miniwsa.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import com.akamai.miniwsa.generator.GeneratorConfig;
import com.akamai.miniwsa.generator.SecurityEventGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Full-pipeline end-to-end test: generates 10k+ events with the data generator, ingests
 * them through the real HTTP API into a production-like ClickHouse, then drives every core
 * endpoint (ingest, summary, samples) and asserts consistent, expected results.
 *
 * <p>Runs <b>only in CI</b> (guarded by {@code E2E_ENABLED}); the CI workflow brings up a
 * ClickHouse service and points the app at it via {@code MINIWSA_STORAGE=clickhouse} +
 * {@code CLICKHOUSE_*}. It is skipped on local {@code ./mvnw test} runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "E2E_ENABLED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullPipelineE2ETest {

    private static final long SEED = 20260520L;
    private static final int EVENT_COUNT = 10_200;
    private static final int WAVE_COUNT = 8;
    private static final int WAVE_SIZE = 75;
    private static final long CONFIG_ID = 14227L;
    private static final int CHUNK_SIZE = 1500;

    private static final Instant WINDOW_FROM = Instant.parse("2026-05-19T00:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2026-05-23T00:00:00Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate clickHouseJdbcTemplate;

    @BeforeAll
    void prepareSchema() throws IOException {
        String schema = new String(
                new ClassPathResource("db/ClickHouseSchema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        clickHouseJdbcTemplate.execute(schema);
        clickHouseJdbcTemplate.execute("TRUNCATE TABLE IF EXISTS mini_wsa.security_events");
    }

    @Test
    void ingestsAndQueriesTenThousandPlusEventsConsistently() throws Exception {
        // 1) Generate a reproducible dataset (> 10k) with attack waves.
        GeneratorConfig config = new GeneratorConfig(
                EVENT_COUNT, CONFIG_ID, WAVE_COUNT, WAVE_SIZE,
                Instant.parse("2026-05-20T00:00:00Z"), Duration.ofHours(24));
        List<SecurityEvent> events = new SecurityEventGenerator(SEED).generate(config);
        assertThat(events).hasSizeGreaterThan(10_000);

        // 2) Ingest through the real HTTP endpoint, in chunks.
        int ingested = 0;
        for (List<SecurityEvent> chunk : partition(events, CHUNK_SIZE)) {
            ingested += ingest(chunk);
        }
        assertThat(ingested).isEqualTo(events.size());

        // 3) Summary: totals are consistent and the waves surface as top attackers.
        JsonNode summary = getJson("/v1/stats/summary?configId=" + CONFIG_ID
                + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO);

        int total = summary.get("totalEvents").asInt();
        assertThat(total).isEqualTo(events.size());
        assertThat(sumValues(summary.get("byAction"))).isEqualTo(total);
        assertThat(sumCounts(summary.get("byCategory"))).isEqualTo(total);

        JsonNode topAttacker = summary.get("topAttackers").get(0);
        assertThat(topAttacker.get("count").asInt()).isGreaterThanOrEqualTo(WAVE_SIZE);
        assertThat(summary.get("topTargetedPaths")).isNotEmpty();

        // 4) Samples: pagination, ordering, and threat-score bounds.
        JsonNode page = getJson("/v1/events/samples?configId=" + CONFIG_ID
                + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO + "&limit=100");
        assertThat(page.get("total").asInt()).isEqualTo(total);
        assertThat(page.get("items")).hasSize(100);

        Instant newest = Instant.parse(page.get("items").get(0).get("timestamp").asText());
        Instant next = Instant.parse(page.get("items").get(1).get("timestamp").asText());
        assertThat(newest).isAfterOrEqualTo(next);

        for (JsonNode item : page.get("items")) {
            assertThat(item.get("threatScore").asInt()).isBetween(0, 100);
        }

        // 5) Cross-API consistency: samples filtered by a category match the summary count.
        String category = summary.get("byCategory").fieldNames().next();
        long summaryCount = summary.get("byCategory").get(category).get("count").asLong();
        JsonNode categoryPage = getJson("/v1/events/samples?configId=" + CONFIG_ID
                + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO + "&category=" + category + "&limit=1");
        assertThat(categoryPage.get("total").asLong()).isEqualTo(summaryCount);

        // 6) Time series: bucket counts over the window sum back to the total.
        JsonNode series = getJson("/v1/stats/timeseries?configId=" + CONFIG_ID
                + "&from=" + WINDOW_FROM + "&to=" + WINDOW_TO + "&interval=1h");
        long bucketed = 0;
        for (JsonNode bucket : series.get("buckets")) {
            bucketed += bucket.get("count").asLong();
        }
        assertThat(bucketed).isEqualTo(total);
    }

    @Test
    void definesAndEvaluatesAlerts() throws Exception {
        // Exercises both stores end-to-end: rules live in the alert-rule store (Redis in CI),
        // while the counts are queried from the events in ClickHouse.
        // A dedicated config + "now"-stamped events so they fall inside the evaluation window
        // (and stay isolated from the 10k historical dataset above).
        long alertConfigId = 99999L;
        Instant now = Instant.now();
        List<SecurityEvent> burst = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            burst.add(dosEvent("al-" + i, now, alertConfigId));
        }
        assertThat(ingest(burst)).isEqualTo(6);

        // One rule the burst exceeds (6 > 5), one it does not (6 < 1000).
        String firingRuleId = defineRule(alertConfigId, "DOS", 5, 60);
        String quietRuleId = defineRule(alertConfigId, "DOS", 1000, 60);

        JsonNode evaluation = getJson("/v1/alerts/evaluate");
        JsonNode fired = firingFor(evaluation, firingRuleId);
        assertThat(fired).isNotNull();
        assertThat(fired.get("actualCount").asInt()).isEqualTo(6);
        assertThat(fired.get("category").asText()).isEqualTo("DOS");
        assertThat(firingFor(evaluation, quietRuleId)).isNull();
    }

    private static SecurityEvent dosEvent(String id, Instant timestamp, long configId) {
        Rule rule = new Rule("1", "DOS_RULE", "DoS pattern", Severity.LOW, RuleCategory.DOS);
        return new SecurityEvent(id, timestamp, configId, "pol", "9.9.9.9", "host", "/x", "GET",
                200, "ua", rule, Action.MONITOR, null, 10L, 20L);
    }

    private String defineRule(long configId, String category, int threshold, int windowMinutes) {
        String body = "{\"configId\":" + configId + ",\"category\":\"" + category + "\",\"threshold\":"
                + threshold + ",\"windowMinutes\":" + windowMinutes + "}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/v1/alerts/define", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asText();
    }

    private static JsonNode firingFor(JsonNode evaluation, String ruleId) {
        for (JsonNode node : evaluation.get("firing")) {
            if (ruleId.equals(node.get("ruleId").asText())) {
                return node;
            }
        }
        return null;
    }

    private int ingest(List<SecurityEvent> chunk) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(chunk), headers);

        ResponseEntity<JsonNode> response = rest.postForEntity("/v1/events/ingest", entity, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("ingested").asInt();
    }

    private JsonNode getJson(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static long sumValues(JsonNode objectNode) {
        long sum = 0;
        for (JsonNode value : objectNode) {
            sum += value.asLong();
        }
        return sum;
    }

    private static long sumCounts(JsonNode byCategory) {
        long sum = 0;
        for (JsonNode entry : byCategory) {
            sum += entry.get("count").asLong();
        }
        return sum;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
