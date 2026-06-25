package com.akamai.miniwsa.config;

import com.akamai.miniwsa.domain.service.AttackTypeClassifier;
import com.akamai.miniwsa.domain.service.ThreatScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the pure domain services as Spring beans. They are kept as plain,
 * framework-free classes (no {@code @Component}); this config is the only place that
 * knows they exist as beans, so the domain stays free of Spring.
 */
@Configuration
public class DomainConfig {

    @Bean
    AttackTypeClassifier attackTypeClassifier() {
        return new AttackTypeClassifier();
    }

    @Bean
    ThreatScoreCalculator threatScoreCalculator() {
        return new ThreatScoreCalculator();
    }
}
