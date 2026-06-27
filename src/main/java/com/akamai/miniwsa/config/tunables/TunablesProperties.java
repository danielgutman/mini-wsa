package com.akamai.miniwsa.config.tunables;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.service.ScoringWeights;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code miniwsa.tunables} at startup, and reused as the validated request/response
 * body for {@code /v1/admin/config}. Maps to/from the immutable {@link Tunables} snapshot.
 */
@ConfigurationProperties(prefix = "miniwsa.tunables")
public record TunablesProperties(
        @NotNull @Valid Scoring scoring,
        @NotNull @Valid RepeatOffender repeatOffender
) {

    public record Scoring(
            @NotEmpty Map<Severity, Integer> severityBase,
            @NotEmpty Map<Action, Integer> actionBonus,
            @PositiveOrZero int sensitivePathBonus,
            @PositiveOrZero int repeatOffenderBonus,
            @Positive int maxScore,
            @NotEmpty List<String> sensitivePaths
    ) {
    }

    public record RepeatOffender(
            @NotNull Duration window,
            @PositiveOrZero long threshold
    ) {
    }

    public Tunables toTunables() {
        return new Tunables(
                new ScoringWeights(scoring.severityBase(), scoring.actionBonus(), scoring.sensitivePathBonus(),
                        scoring.repeatOffenderBonus(), scoring.maxScore(), scoring.sensitivePaths()),
                repeatOffender.window(),
                repeatOffender.threshold());
    }

    public static TunablesProperties fromTunables(Tunables tunables) {
        ScoringWeights w = tunables.scoring();
        return new TunablesProperties(
                new Scoring(w.severityBase(), w.actionBonus(), w.sensitivePathBonus(),
                        w.repeatOffenderBonus(), w.maxScore(), w.sensitivePaths()),
                new RepeatOffender(tunables.repeatOffenderWindow(), tunables.repeatOffenderThreshold()));
    }
}
