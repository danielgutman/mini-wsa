package com.akamai.miniwsa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the OpenAPI spec is generated from the code: {@code /v3/api-docs} carries the document
 * metadata and every endpoint, and {@code /swagger-ui.html} serves the interactive UI.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsExposeMetadataAndEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Mini WSA API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                // Relative server so "Try it out" hits the page origin, not a hard-coded host/port.
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.paths['/v1/events/ingest'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/stats/summary'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/stats/timeseries'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/events/samples'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/alerts/define'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/alerts/evaluate'].get").exists());
    }

    @Test
    void summaryParamsAreFlattenedAndHideTheValidationGetter() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // configId/from/to surface as individual query params...
                .andExpect(jsonPath("$.paths['/v1/stats/summary'].get.parameters[?(@.name=='from')]").exists())
                .andExpect(jsonPath("$.paths['/v1/stats/summary'].get.parameters[?(@.name=='to')]").exists())
                // ...but the @AssertTrue 'rangeOrdered' validation getter must not be exposed.
                .andExpect(jsonPath("$.paths['/v1/stats/summary'].get.parameters[?(@.name=='rangeOrdered')]").doesNotExist());
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
