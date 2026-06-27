package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /v1/alerts/define}: "more than {@code threshold} events of
 * {@code category} within the last {@code windowMinutes} minutes". {@code configId} is optional
 * (null scopes the rule across all configurations).
 */
public record DefineAlertRequest(
        Long configId,
        @NotNull(message = "'category' is required") RuleCategory category,
        @NotNull(message = "'threshold' is required")
        @Min(value = 1, message = "threshold must be >= 1") Integer threshold,
        @NotNull(message = "'windowMinutes' is required")
        @Min(value = 1, message = "windowMinutes must be >= 1") Integer windowMinutes
) {

    public AlertRule toDomain() {
        return new AlertRule(null, configId, category, threshold, windowMinutes);
    }
}
