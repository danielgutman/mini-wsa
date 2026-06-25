package com.akamai.miniwsa.domain.enums;

/**
 * Severity reported by the matched rule. Contributes the base portion of the
 * threat score (see {@code ThreatScoreCalculator}).
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
