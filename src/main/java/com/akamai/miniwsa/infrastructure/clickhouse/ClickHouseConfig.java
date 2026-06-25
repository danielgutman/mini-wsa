package com.akamai.miniwsa.infrastructure.clickhouse;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the ClickHouse {@link DataSource} and {@link JdbcTemplate}, active only when
 * {@code miniwsa.storage=clickhouse}. In the default (in-memory) profile none of this
 * is created, so the app needs no database to boot.
 */
@Configuration
@ConditionalOnProperty(name = "miniwsa.storage", havingValue = "clickhouse")
@EnableConfigurationProperties(ClickHouseConfig.ClickHouseProperties.class)
public class ClickHouseConfig {

    @Bean
    DataSource clickHouseDataSource(ClickHouseProperties properties) {
        return DataSourceBuilder.create()
                .driverClassName("com.clickhouse.jdbc.ClickHouseDriver")
                .url(properties.url())
                .username(properties.username())
                .password(properties.password())
                .build();
    }

    @Bean
    JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        return new JdbcTemplate(clickHouseDataSource);
    }

    /** Connection settings for the ClickHouse adapter. */
    @ConfigurationProperties(prefix = "miniwsa.clickhouse")
    public record ClickHouseProperties(String url, String username, String password) {
    }
}
