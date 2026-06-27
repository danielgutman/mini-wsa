package com.akamai.miniwsa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API integration test for the alerting endpoints over the in-memory store. It ingests a burst of
 * <b>recent</b> events (stamped "now" so they fall inside the evaluation window), defines two rules
 * against them, and asserts {@code evaluate} fires only the rule whose threshold is exceeded.
 *
 * <p>Uses a dedicated {@code configId} and asserts firing rules by their returned id, so it is
 * robust against the shared in-memory store.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AlertControllerTest {

    private static final long CONFIG_ID = 788001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void definesRulesAndEvaluatesFiringOnly() throws Exception {
        ingestRecentDosEvents(6);

        String firingRuleId = define(5);     // 6 > 5    -> fires
        String quietRuleId = define(1000);   // 6 < 1000 -> does not fire

        JsonNode result = objectMapper.readTree(
                mockMvc.perform(get("/v1/alerts/evaluate"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        JsonNode fired = findByRuleId(result.get("firing"), firingRuleId);
        assertThat(fired).isNotNull();
        assertThat(fired.get("actualCount").asInt()).isEqualTo(6);
        assertThat(fired.get("threshold").asInt()).isEqualTo(5);
        assertThat(fired.get("category").asText()).isEqualTo("DOS");

        assertThat(findByRuleId(result.get("firing"), quietRuleId)).isNull();
    }

    @Test
    void rejectsInvalidRuleDefinition() throws Exception {
        // threshold must be >= 1
        mockMvc.perform(post("/v1/alerts/define").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DOS\",\"threshold\":0,\"windowMinutes\":5}"))
                .andExpect(status().isBadRequest());
        // category is required
        mockMvc.perform(post("/v1/alerts/define").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\":5,\"windowMinutes\":5}"))
                .andExpect(status().isBadRequest());
    }

    private void ingestRecentDosEvents(int count) throws Exception {
        String now = Instant.now().toString();
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                body.append(",");
            }
            body.append("{\"eventId\":\"al-").append(i).append("\",\"timestamp\":\"").append(now)
                    .append("\",\"configId\":").append(CONFIG_ID)
                    .append(",\"clientIp\":\"9.9.9.9\",\"path\":\"/x\",\"method\":\"GET\",\"statusCode\":200,")
                    .append("\"rule\":{\"id\":\"1\",\"severity\":\"LOW\",\"category\":\"DOS\"},\"action\":\"MONITOR\"}");
        }
        body.append("]");
        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated());
    }

    private String define(int threshold) throws Exception {
        String body = "{\"configId\":" + CONFIG_ID + ",\"category\":\"DOS\",\"threshold\":" + threshold
                + ",\"windowMinutes\":60}";
        String response = mockMvc.perform(post("/v1/alerts/define")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private static JsonNode findByRuleId(JsonNode firingArray, String ruleId) {
        for (JsonNode node : firingArray) {
            if (ruleId.equals(node.get("ruleId").asText())) {
                return node;
            }
        }
        return null;
    }
}
