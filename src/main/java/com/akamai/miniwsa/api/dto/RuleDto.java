package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.Rule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request-side representation of the {@code rule} object on an incoming event.
 *
 * <p>Validation lives here (API boundary), not in the domain {@link Rule}.
 * {@code severity} and {@code category} are typed as enums so an unknown value
 * fails JSON binding and is reported as a 400 by the central error handler.
 */
public record RuleDto(
        @NotBlank String id,
        String name,
        String message,
        @NotNull Severity severity,
        @NotNull RuleCategory category
) {

    public Rule toDomain() {
        return new Rule(id, name, message, severity, category);
    }
}
