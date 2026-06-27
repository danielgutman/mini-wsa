package com.akamai.miniwsa.api.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.akamai.miniwsa.config.tunables.Tunables;
import com.akamai.miniwsa.config.tunables.TunablesHolder;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.service.ScoringWeights;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for {@code /v1/admin/config}: proves the enrichment rules can be swapped at
 * runtime and take effect on the next ingested event — no restart. The shared {@link TunablesHolder}
 * is reset to defaults after each test so the live mutation does not leak into other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminConfigControllerTest {

    private static final Tunables DEFAULTS = new Tunables(
            new ScoringWeights(
                    Map.of(Severity.CRITICAL, 40, Severity.HIGH, 30, Severity.MEDIUM, 20, Severity.LOW, 10),
                    Map.of(Action.DENY, 20, Action.ALERT, 10, Action.MONITOR, 0),
                    15, 15, 100, List.of("/admin", "/login")),
            Duration.ofMinutes(10), 5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TunablesHolder holder;

    @AfterEach
    void resetTunables() {
        holder.replaceWith(DEFAULTS);
    }

    @Test
    void liveUpdateChangesScoringWithoutRestart() throws Exception {
        long config = 990001L;

        // Before: CRITICAL(40) + DENY(20) + /login(15) = 75 with the default weights.
        ingest("before", "2026-05-20T10:00:00Z", config);
        mockMvc.perform(get("/v1/events/samples").param("configId", "990001").param("limit", "1"))
                .andExpect(jsonPath("$.items[0].eventId").value("before"))
                .andExpect(jsonPath("$.items[0].threatScore").value(75));

        // Live-swap: drop the CRITICAL base from 40 to 10.
        String newConfig = """
                {
                  "scoring": {
                    "severityBase": {"CRITICAL":10,"HIGH":30,"MEDIUM":20,"LOW":10},
                    "actionBonus": {"DENY":20,"ALERT":10,"MONITOR":0},
                    "sensitivePathBonus":15,"repeatOffenderBonus":15,"maxScore":100,
                    "sensitivePaths":["/admin","/login"]
                  },
                  "repeatOffender": {"window":"PT10M","threshold":5}
                }
                """;
        mockMvc.perform(put("/v1/admin/config").contentType(MediaType.APPLICATION_JSON).content(newConfig))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoring.severityBase.CRITICAL").value(10));

        // After: same attributes now score 10 + 20 + 15 = 45 — no restart in between.
        ingest("after", "2026-05-20T10:05:00Z", config);
        mockMvc.perform(get("/v1/events/samples").param("configId", "990001").param("limit", "1"))
                .andExpect(jsonPath("$.items[0].eventId").value("after"))
                .andExpect(jsonPath("$.items[0].threatScore").value(45));
    }

    @Test
    void getReturnsCurrentConfig() throws Exception {
        mockMvc.perform(get("/v1/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoring.severityBase.CRITICAL").value(40))
                .andExpect(jsonPath("$.scoring.actionBonus.DENY").value(20))
                .andExpect(jsonPath("$.repeatOffender.window").value("PT10M"))
                .andExpect(jsonPath("$.repeatOffender.threshold").value(5));
    }

    @Test
    void rejectsInvalidConfig() throws Exception {
        // maxScore must be positive; 0 fails validation -> 400.
        String invalid = """
                {
                  "scoring": {
                    "severityBase": {"CRITICAL":40},"actionBonus": {"DENY":20},
                    "sensitivePathBonus":15,"repeatOffenderBonus":15,"maxScore":0,"sensitivePaths":["/admin"]
                  },
                  "repeatOffender": {"window":"PT10M","threshold":5}
                }
                """;
        mockMvc.perform(put("/v1/admin/config").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }

    private void ingest(String eventId, String timestamp, long configId) throws Exception {
        String event = """
                {"eventId":"%s","timestamp":"%s","configId":%d,"clientIp":"203.0.113.42",
                 "path":"/api/v1/login","method":"POST","statusCode":403,
                 "rule":{"id":"950001","severity":"CRITICAL","category":"INJECTION"},"action":"DENY"}
                """.formatted(eventId, timestamp, configId);
        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isCreated());
    }
}
