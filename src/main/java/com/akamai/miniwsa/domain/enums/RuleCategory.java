package com.akamai.miniwsa.domain.enums;

/**
 * Category of the security rule that matched an event. Drives attack-type
 * classification (see {@code AttackTypeClassifier}).
 */
public enum RuleCategory {
    INJECTION,
    XSS,
    PROTOCOL_VIOLATION,
    DATA_LEAKAGE,
    BOT,
    DOS,
    RATE_LIMIT
}
