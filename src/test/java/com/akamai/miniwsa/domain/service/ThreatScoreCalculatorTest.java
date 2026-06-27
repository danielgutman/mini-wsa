package com.akamai.miniwsa.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link ThreatScoreCalculator}, covering each scoring component
 * (severity base, action bonus, sensitive-path bonus, repeat-offender bonus) and
 * the cap, using the default scoring weights.
 */
class ThreatScoreCalculatorTest {

    private static final ScoringWeights WEIGHTS = new ScoringWeights(
            Map.of(Severity.CRITICAL, 40, Severity.HIGH, 30, Severity.MEDIUM, 20, Severity.LOW, 10),
            Map.of(Action.DENY, 20, Action.ALERT, 10, Action.MONITOR, 0),
            15, 15, 100, List.of("/admin", "/login"));

    private final ThreatScoreCalculator calculator = new ThreatScoreCalculator();

    @Nested
    class SeverityBase {
        // MONITOR (+0) and a neutral path isolate the severity contribution.
        @ParameterizedTest
        @CsvSource({"CRITICAL,40", "HIGH,30", "MEDIUM,20", "LOW,10"})
        void severityProvidesTheBaseScore(Severity severity, int expected) {
            assertThat(calculator.calculate(severity, Action.MONITOR, "/", false, WEIGHTS)).isEqualTo(expected);
        }
    }

    @Nested
    class ActionBonus {
        // LOW base (10) is constant, so the difference is the action bonus.
        @ParameterizedTest
        @CsvSource({"DENY,30", "ALERT,20", "MONITOR,10"})
        void actionAddsToTheScore(Action action, int expected) {
            assertThat(calculator.calculate(Severity.LOW, action, "/", false, WEIGHTS)).isEqualTo(expected);
        }
    }

    @Nested
    class SensitivePathBonus {
        @Test
        void loginPathAddsFifteen() {
            // LOW (10) + MONITOR (0) + /login (15) = 25
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, "/api/v1/login", false, WEIGHTS)).isEqualTo(25);
        }

        @Test
        void adminPathAddsFifteen() {
            // LOW (10) + MONITOR (0) + /admin (15) = 25
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, "/admin/users", false, WEIGHTS)).isEqualTo(25);
        }

        @Test
        void neutralPathAddsNothing() {
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, "/api/v1/search", false, WEIGHTS)).isEqualTo(10);
        }

        @Test
        void nullPathIsTreatedAsNeutral() {
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, null, false, WEIGHTS)).isEqualTo(10);
        }
    }

    @Nested
    class RepeatOffenderBonus {
        @Test
        void repeatOffenderAddsFifteen() {
            int baseline = calculator.calculate(Severity.LOW, Action.MONITOR, "/", false, WEIGHTS);
            int withFlag = calculator.calculate(Severity.LOW, Action.MONITOR, "/", true, WEIGHTS);
            assertThat(withFlag - baseline).isEqualTo(15);
        }
    }

    @Nested
    class Cap {
        @Test
        void maximalCombinationStaysWithinCap() {
            // CRITICAL(40) + DENY(20) + /login(15) + repeatOffender(15) = 90 — the highest reachable
            // score under the default weights, so the 100 cap is defensive (asserted as an upper bound).
            int max = calculator.calculate(Severity.CRITICAL, Action.DENY, "/login", true, WEIGHTS);
            assertThat(max).isEqualTo(90).isLessThanOrEqualTo(100);
        }

        @Test
        void capIsApplied() {
            // Tiny cap proves Math.min: CRITICAL(40)+DENY(20) = 60, capped to 50.
            ScoringWeights capped = new ScoringWeights(WEIGHTS.severityBase(), WEIGHTS.actionBonus(),
                    15, 15, 50, WEIGHTS.sensitivePaths());
            assertThat(calculator.calculate(Severity.CRITICAL, Action.DENY, "/", false, capped)).isEqualTo(50);
        }
    }
}
