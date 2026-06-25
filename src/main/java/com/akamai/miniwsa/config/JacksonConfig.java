package com.akamai.miniwsa.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON binding tweaks. Enabling {@code ACCEPT_SINGLE_VALUE_AS_ARRAY} lets the
 * ingestion endpoint bind both a single event object and a JSON array to the same
 * {@code List<IngestEventRequest>} parameter, so the controller needs no custom
 * single-vs-array parsing.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer acceptSingleValueAsArray() {
        return builder -> builder.featuresToEnable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
    }
}
