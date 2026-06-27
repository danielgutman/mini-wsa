package com.akamai.miniwsa.config.tunables;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link TunablesProperties} from {@code application.yml} and seeds the {@link TunablesHolder}
 * with the initial snapshot. The holder is the single source of truth thereafter; the admin
 * endpoint swaps its contents at runtime.
 */
@Configuration
@EnableConfigurationProperties(TunablesProperties.class)
public class TunablesConfig {

    @Bean
    TunablesHolder tunablesHolder(TunablesProperties properties) {
        return new TunablesHolder(properties.toTunables());
    }
}
