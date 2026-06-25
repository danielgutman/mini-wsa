package com.akamai.miniwsa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API integration test for {@code GET /v1/events/samples} over the in-memory store. The
 * store is a shared singleton, so each test seeds under its own {@code configId} and
 * filters by it — keeping counts deterministic regardless of order. Asserts
 * timestamp-descending ordering, pagination (limit/offset, max-limit clamp), filtering,
 * the total count, and 400s for invalid pagination.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SamplesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** Ingests 3 events under {@code configId}: a1 < a2 < a3 by timestamp (a3 newest). */
    private void seed(long configId) throws Exception {
        String batch = """
                [
                  {"eventId":"a1","timestamp":"2026-05-20T10:00:00Z","configId":%1$d,"clientIp":"1.1.1.1",
                   "path":"/login","method":"POST","statusCode":403,
                   "rule":{"id":"1","severity":"CRITICAL","category":"INJECTION"},"action":"DENY"},
                  {"eventId":"a2","timestamp":"2026-05-20T10:05:00Z","configId":%1$d,"clientIp":"1.1.1.1",
                   "path":"/login","method":"POST","statusCode":403,
                   "rule":{"id":"1","severity":"CRITICAL","category":"INJECTION"},"action":"DENY"},
                  {"eventId":"a3","timestamp":"2026-05-20T10:10:00Z","configId":%1$d,"clientIp":"2.2.2.2",
                   "path":"/x","method":"GET","statusCode":200,
                   "rule":{"id":"2","severity":"LOW","category":"BOT"},"action":"MONITOR"}
                ]
                """.formatted(configId);
        mockMvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isCreated());
    }

    @Test
    void returnsAllMatchesNewestFirst() throws Exception {
        seed(8801);
        mockMvc.perform(get("/v1/events/samples").param("configId", "8801"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].eventId").value("a3"))
                .andExpect(jsonPath("$.items[0].attackType").value("Bot Activity"))
                .andExpect(jsonPath("$.items[0].rule.category").value("BOT"))
                .andExpect(jsonPath("$.items[2].eventId").value("a1"));
    }

    @Test
    void paginatesWithLimitAndOffset() throws Exception {
        seed(8802);
        mockMvc.perform(get("/v1/events/samples").param("configId", "8802").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].eventId").value("a3"))
                .andExpect(jsonPath("$.items[1].eventId").value("a2"));

        mockMvc.perform(get("/v1/events/samples").param("configId", "8802").param("limit", "2").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value("a1"));
    }

    @Test
    void filtersByCategory() throws Exception {
        seed(8803);
        mockMvc.perform(get("/v1/events/samples").param("configId", "8803").param("category", "BOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value("a3"));
    }

    @Test
    void clampsLimitToMax100() throws Exception {
        seed(8804);
        mockMvc.perform(get("/v1/events/samples").param("configId", "8804").param("limit", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.items.length()").value(Matchers.lessThanOrEqualTo(100)));
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/v1/events/samples").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/events/samples").param("offset", "-1"))
                .andExpect(status().isBadRequest());
    }
}
