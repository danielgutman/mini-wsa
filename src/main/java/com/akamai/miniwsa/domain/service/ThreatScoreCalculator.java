package com.akamai.miniwsa.domain.service;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.Severity;

/**
 * Computes the threat score (integer 0..100) for an event.
 *
 * <p>Pure domain logic — deterministic and IO-free. Crucially, the
 * {@code repeatOffender} flag is an <em>input</em>: deciding it requires a
 * historical count from storage, which is an effect the application layer owns.
 * Keeping that decision out of here lets the scoring rules stay unit-testable
 * without a database. See the IO policy in the implementation brief.
 *
 * <p>Score composition:
 * <ul>
 *   <li>severity base — CRITICAL 40, HIGH 30, MEDIUM 20, LOW 10</li>
 *   <li>action bonus  — DENY +20, ALERT +10, MONITOR +0</li>
 *   <li>sensitive path — +15 if the path contains {@code /admin} or {@code /login}</li>
 *   <li>repeat offender — +15 when the flag is set</li>
 * </ul>
 * The total is capped at {@value #MAX_SCORE}.
 */
public final class ThreatScoreCalculator {

    static final int MAX_SCORE = 100;
    static final int SENSITIVE_PATH_BONUS = 15;
    static final int REPEAT_OFFENDER_BONUS = 15;

    public int calculate(Severity severity, Action action, String path, boolean repeatOffender) {
        int score = severityBase(severity) + actionBonus(action);

        if (isSensitivePath(path)) {
            score += SENSITIVE_PATH_BONUS;
        }
        if (repeatOffender) {
            score += REPEAT_OFFENDER_BONUS;
        }

        return Math.min(score, MAX_SCORE);
    }

    private int severityBase(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 40;
            case HIGH -> 30;
            case MEDIUM -> 20;
            case LOW -> 10;
        };
    }

    private int actionBonus(Action action) {
        return switch (action) {
            case DENY -> 20;
            case ALERT -> 10;
            case MONITOR -> 0;
        };
    }

    private boolean isSensitivePath(String path) {
        if (path == null) {
            return false;
        }
        return path.contains("/admin") || path.contains("/login");
    }
}
