package com.akamai.miniwsa.domain.enums;

/**
 * Enforcement action taken for an event. Contributes an additive portion of the
 * threat score (see {@code ThreatScoreCalculator}).
 */
public enum Action {
    DENY,
    ALERT,
    MONITOR
}
