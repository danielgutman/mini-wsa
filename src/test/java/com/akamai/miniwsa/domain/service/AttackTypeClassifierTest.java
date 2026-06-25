package com.akamai.miniwsa.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.domain.enums.RuleCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link AttackTypeClassifier}: every category maps to the exact
 * attack-type string from the PRD, and the mapping is total (no category is left
 * unclassified).
 */
class AttackTypeClassifierTest {

    private final AttackTypeClassifier classifier = new AttackTypeClassifier();

    @ParameterizedTest
    @CsvSource({
            "INJECTION,SQL/Command Injection",
            "XSS,Cross-Site Scripting",
            "PROTOCOL_VIOLATION,Protocol Anomaly",
            "DATA_LEAKAGE,Data Exfiltration",
            "BOT,Bot Activity",
            "DOS,Denial of Service",
            "RATE_LIMIT,Rate Limiting"
    })
    void mapsEachCategoryToItsAttackType(RuleCategory category, String expected) {
        assertThat(classifier.classify(category)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(RuleCategory.class)
    void classifiesEveryCategoryToNonBlankValue(RuleCategory category) {
        assertThat(classifier.classify(category)).isNotBlank();
    }
}
