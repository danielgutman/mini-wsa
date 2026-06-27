package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response for a defined alert rule ({@code POST /v1/alerts/define}). {@code configId} is omitted
 * from the JSON when the rule applies to all configurations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertRuleResponse(
        String id,
        Long configId,
        RuleCategory category,
        int threshold,
        int windowMinutes
) {

    public static AlertRuleResponse from(AlertRule rule) {
        return new AlertRuleResponse(
                rule.id(), rule.configId(), rule.category(), rule.threshold(), rule.windowMinutes());
    }
}
