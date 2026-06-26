package com.akamai.miniwsa.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
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
 * API integration test for {@code POST /v1/events/ingest}, booting the full Spring
 * context over the default in-memory storage adapter (no database required). Covers
 * single + batch acceptance and the centralized 400 ProblemDetail responses with
 * field-level {@code errors}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String VALID_EVENT = """
            {
              "eventId": "evt-001",
              "timestamp": "2026-05-20T14:32:10Z",
              "configId": 14227,
              "clientIp": "203.0.113.42",
              "path": "/api/v1/login",
              "method": "POST",
              "statusCode": 403,
              "rule": {"id": "950001", "severity": "CRITICAL", "category": "INJECTION"},
              "action": "DENY"
            }
            """;

    @Test
    void acceptsSingleEvent() throws Exception {
        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ingested").value(1));
    }

    @Test
    void acceptsBatchOfEvents() throws Exception {
        String batch = "[" + VALID_EVENT + "," + VALID_EVENT.replace("evt-001", "evt-002") + "]";

        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ingested").value(2));
    }

    @Test
    void rejectsMissingRequiredField() throws Exception {
        String missingRule = VALID_EVENT.replaceAll("(?s)\"rule\".*?\\},", "");

        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(missingRule))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("[0].rule")));
    }

    @Test
    void rejectsInvalidEnumValue() throws Exception {
        String badEnum = VALID_EVENT.replace("\"INJECTION\"", "\"NOT_A_CATEGORY\"");

        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(badEnum))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void rejectsEmptyBatch() throws Exception {
        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("events")));
    }

    @Test
    void rejectsOutOfRangeNumericFields() throws Exception {
        // statusCode beyond HTTP range and a negative configId — parseable, but invalid.
        // Must be a clean 400, not a 500 from the (unsigned) ClickHouse columns.
        String bad = VALID_EVENT
                .replace("\"statusCode\": 403", "\"statusCode\": 99999")
                .replace("\"configId\": 14227", "\"configId\": -5");

        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("[0].statusCode")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("[0].configId")));
    }
}
