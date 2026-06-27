package com.akamai.miniwsa.application.alerting;

import java.time.Instant;

/**
 * A rule that is currently firing: the rule plus the observed {@code actualCount} over the
 * evaluated window {@code [windowFrom, windowTo)}.
 */
public record FiringAlert(AlertRule rule, long actualCount, Instant windowFrom, Instant windowTo) {
}
