package com.akamai.miniwsa.application.alerting;

import java.time.Instant;
import java.util.List;

/**
 * Result of evaluating all defined rules at {@code evaluatedAt}: the subset that are firing.
 */
public record AlertEvaluation(Instant evaluatedAt, List<FiringAlert> firing) {
}
