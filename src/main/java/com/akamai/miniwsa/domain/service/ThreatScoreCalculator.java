package com.akamai.miniwsa.domain.service;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.Severity;
import java.util.List;

/**
 * Computes the threat score (integer 0..{@code maxScore}) for an event.
 *
 * <p>Pure domain logic — deterministic and IO-free. Two things are passed in rather than
 * hard-coded, both to keep this class pure and unit-testable:
 * <ul>
 *   <li>{@code repeatOffender} — deciding it needs a historical count from storage (an effect
 *       the application layer owns);</li>
 *   <li>{@link ScoringWeights} — the tunable weights, supplied by the application layer from
 *       configuration so they can change at runtime without touching this class.</li>
 * </ul>
 *
 * <p>Score = severity base + action bonus + sensitive-path bonus (if the path matches) +
 * repeat-offender bonus (if flagged), capped at {@code weights.maxScore()}.
 */
public final class ThreatScoreCalculator {

    public int calculate(Severity severity, Action action, String path, boolean repeatOffender,
                         ScoringWeights weights) {
        int score = weights.severityBase().getOrDefault(severity, 0)
                + weights.actionBonus().getOrDefault(action, 0);

        if (isSensitivePath(path, weights.sensitivePaths())) {
            score += weights.sensitivePathBonus();
        }
        if (repeatOffender) {
            score += weights.repeatOffenderBonus();
        }

        return Math.min(score, weights.maxScore());
    }

    private static boolean isSensitivePath(String path, List<String> sensitivePaths) {
        if (path == null) {
            return false;
        }
        return sensitivePaths.stream().anyMatch(path::contains);
    }
}
