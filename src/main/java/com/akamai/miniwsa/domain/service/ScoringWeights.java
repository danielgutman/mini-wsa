package com.akamai.miniwsa.domain.service;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.Severity;
import java.util.List;
import java.util.Map;

/**
 * The tunable weights {@link ThreatScoreCalculator} uses. A pure domain value object — the
 * application layer supplies the current instance (sourced from configuration, swappable at
 * runtime), so the calculator stays free of Spring/config while the rules remain adjustable.
 *
 * @param severityBase        base score per severity
 * @param actionBonus         additive bonus per action
 * @param sensitivePathBonus  added when the path contains any {@code sensitivePaths} fragment
 * @param repeatOffenderBonus added when the repeat-offender flag is set
 * @param maxScore            the cap applied to the total
 * @param sensitivePaths      path fragments that count as sensitive (e.g. {@code /admin})
 */
public record ScoringWeights(
        Map<Severity, Integer> severityBase,
        Map<Action, Integer> actionBonus,
        int sensitivePathBonus,
        int repeatOffenderBonus,
        int maxScore,
        List<String> sensitivePaths
) {

    public ScoringWeights {
        severityBase = Map.copyOf(severityBase);
        actionBonus = Map.copyOf(actionBonus);
        sensitivePaths = List.copyOf(sensitivePaths);
    }
}
