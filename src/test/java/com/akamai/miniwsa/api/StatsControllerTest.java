package com.akamai.miniwsa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API integration test for {@code GET /v1/stats/summary} over the in-memory store: it
 * ingests a known set of events through the real ingestion endpoint, then asserts the
 * aggregated totals, per-category/per-action breakdowns, and top attackers/paths.
 *
 * <p>The in-memory store is a shared singleton, so this test uses a dedicated
 * {@code configId} and filters by it — counts stay deterministic regardless of test
 * order or any events left by other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatsControllerTest {

    private static final String CONFIG_ID = "777001";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void summarizesEventsInRange() throws Exception {
        // 3 events under CONFIG_ID: two from IP .42 hitting /login (INJECTION/DENY),
        // one from .7 hitting /x (BOT/MONITOR).
        String batch = """
                [
                  {"eventId":"s1","timestamp":"2026-05-20T10:00:00Z","configId":777001,"clientIp":"203.0.113.42",
                   "path":"/login","method":"POST","statusCode":403,
                   "rule":{"id":"1","severity":"CRITICAL","category":"INJECTION"},"action":"DENY"},
                  {"eventId":"s2","timestamp":"2026-05-20T10:05:00Z","configId":777001,"clientIp":"203.0.113.42",
                   "path":"/login","method":"POST","statusCode":403,
                   "rule":{"id":"1","severity":"CRITICAL","category":"INJECTION"},"action":"DENY"},
                  {"eventId":"s3","timestamp":"2026-05-20T10:10:00Z","configId":777001,"clientIp":"10.0.0.7",
                   "path":"/x","method":"GET","statusCode":200,
                   "rule":{"id":"2","severity":"LOW","category":"BOT"},"action":"MONITOR"}
                ]
                """;
        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/stats/summary")
                        .param("configId", CONFIG_ID)
                        .param("from", "2026-05-20T00:00:00Z")
                        .param("to", "2026-05-21T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configId").value(777001))
                .andExpect(jsonPath("$.totalEvents").value(3))
                .andExpect(jsonPath("$.byCategory.INJECTION.count").value(2))
                // CRITICAL(40)+DENY(20)+/login(15) = 75 for both INJECTION events
                .andExpect(jsonPath("$.byCategory.INJECTION.avgThreatScore").value(75.0))
                .andExpect(jsonPath("$.byAction.DENY").value(2))
                .andExpect(jsonPath("$.byAction.MONITOR").value(1))
                .andExpect(jsonPath("$.topAttackers[0].clientIp").value("203.0.113.42"))
                .andExpect(jsonPath("$.topAttackers[0].count").value(2))
                .andExpect(jsonPath("$.topTargetedPaths[0].path").value("/login"))
                .andExpect(jsonPath("$.topTargetedPaths[0].count").value(2));
    }

    @Test
    void bucketsEventsByInterval() throws Exception {
        // 3 events at 10:00, 10:05, 10:10 under a dedicated config; 5m buckets -> 1 each.
        String batch = """
                [
                  {"eventId":"t1","timestamp":"2026-05-20T10:00:00Z","configId":779001,"clientIp":"1.1.1.1",
                   "path":"/x","method":"GET","statusCode":200,"rule":{"id":"1","severity":"LOW","category":"BOT"},"action":"MONITOR"},
                  {"eventId":"t2","timestamp":"2026-05-20T10:05:00Z","configId":779001,"clientIp":"1.1.1.1",
                   "path":"/x","method":"GET","statusCode":200,"rule":{"id":"1","severity":"LOW","category":"BOT"},"action":"MONITOR"},
                  {"eventId":"t3","timestamp":"2026-05-20T10:10:00Z","configId":779001,"clientIp":"1.1.1.1",
                   "path":"/x","method":"GET","statusCode":200,"rule":{"id":"1","severity":"LOW","category":"BOT"},"action":"MONITOR"}
                ]
                """;
        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/stats/timeseries")
                        .param("configId", "779001")
                        .param("from", "2026-05-20T10:00:00Z")
                        .param("to", "2026-05-20T10:15:00Z")
                        .param("interval", "5m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configId").value(779001))
                .andExpect(jsonPath("$.interval").value("5m"))
                .andExpect(jsonPath("$.buckets.length()").value(3))
                .andExpect(jsonPath("$.buckets[0].from").value("2026-05-20T10:00:00Z"))
                .andExpect(jsonPath("$.buckets[0].to").value("2026-05-20T10:05:00Z"))
                .andExpect(jsonPath("$.buckets[0].count").value(1))
                .andExpect(jsonPath("$.buckets[1].count").value(1))
                .andExpect(jsonPath("$.buckets[2].count").value(1));
    }

    @Test
    void rejectsInvalidInterval() throws Exception {
        mockMvc.perform(get("/v1/stats/timeseries")
                        .param("from", "2026-05-20T10:00:00Z")
                        .param("to", "2026-05-20T11:00:00Z")
                        .param("interval", "2m"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidTimeRange() throws Exception {
        mockMvc.perform(get("/v1/stats/summary")
                        .param("from", "2026-05-21T00:00:00Z")
                        .param("to", "2026-05-20T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingRequiredParam() throws Exception {
        mockMvc.perform(get("/v1/stats/summary").param("from", "2026-05-20T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
