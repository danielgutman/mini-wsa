package com.akamai.miniwsa.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.Severity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link ThreatScoreCalculator}, covering each scoring component
 * (severity base, action bonus, sensitive-path bonus, repeat-offender bonus) and
 * the 0..100 cap, per the PRD.
 */
class ThreatScoreCalculatorTest {

    private final ThreatScoreCalculator calculator = new ThreatScoreCalculator();

    @Nested
    class SeverityBase {
        // MONITOR (+0) and a neutral path isolate the severity contribution.
        @ParameterizedTest
        @CsvSource({"CRITICAL,40", "HIGH,30", "MEDIUM,20", "LOW,10"})
        void severityProvidesTheBaseScore(Severity severity, int expected) {
            assertThat(calculator.calculate(severity, Action.MONITOR, "/", false)).isEqualTo(expected);
        }
    }

    @Nested
    class ActionBonus {
        // LOW base (10) is constant, so the difference is the action bonus.
        @ParameterizedTest
        @CsvSource({"DENY,30", "ALERT,20", "MONITOR,10"})
        void actionAddsToTheScore(Action action, int expected) {
            assertThat(calculator.calculate(Severity.LOW, action, "/", false)).isEqualTo(expected);
        }
    }

    @Nested
    class SensitivePathBonus {
        @Test
        void loginPathAddsFifteen() {
            // LOW (10) + MONITOR (0) + /login (15) = 25
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, "/api/v1/login", false)).isEqualTo(25);
        }

        @Test
        void adminPathAddsFifteen() {
            // LOW (10) + MONITOR (0) + /admin (15) = 25
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, "/admin/users", false)).isEqualTo(25);
        }

        @Test
        void neutralPathAddsNothing() {
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, "/api/v1/search", false)).isEqualTo(10);
        }

        @Test
        void nullPathIsTreatedAsNeutral() {
            assertThat(calculator.calculate(Severity.LOW, Action.MONITOR, null, false)).isEqualTo(10);
        }
    }

    @Nested
    class RepeatOffenderBonus {
        @Test
        void repeatOffenderAddsFifteen() {
            int baseline = calculator.calculate(Severity.LOW, Action.MONITOR, "/", false);
            int withFlag = calculator.calculate(Severity.LOW, Action.MONITOR, "/", true);
            assertThat(withFlag - baseline).isEqualTo(15);
        }
    }

    @Nested
    class Cap {
        @Test
        void maximalCombinationStaysWithinCap() {
            // CRITICAL(40) + DENY(20) + /login(15) + repeatOffender(15) = 90.
            // This is the highest reachable score under the current rules, so the
            // 100 cap is defensive; it is asserted here as an upper bound.
            int max = calculator.calculate(Severity.CRITICAL, Action.DENY, "/login", true);
            assertThat(max).isEqualTo(90).isLessThanOrEqualTo(100);
        }
    }
}
