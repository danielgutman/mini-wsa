package com.akamai.miniwsa.domain.service;

import com.akamai.miniwsa.domain.enums.RuleCategory;

/**
 * Maps a {@link RuleCategory} to a human-readable attack type string.
 *
 * <p>Pure domain logic: no IO, no framework dependencies, fully deterministic.
 * The exhaustive {@code switch} means adding a new category will not compile
 * until it is classified here.
 */
public final class AttackTypeClassifier {

    public String classify(RuleCategory category) {
        return switch (category) {
            case INJECTION -> "SQL/Command Injection";
            case XSS -> "Cross-Site Scripting";
            case PROTOCOL_VIOLATION -> "Protocol Anomaly";
            case DATA_LEAKAGE -> "Data Exfiltration";
            case BOT -> "Bot Activity";
            case DOS -> "Denial of Service";
            case RATE_LIMIT -> "Rate Limiting";
        };
    }
}
