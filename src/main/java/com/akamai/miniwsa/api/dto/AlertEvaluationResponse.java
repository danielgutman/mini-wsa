package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.application.alerting.AlertEvaluation;
import com.akamai.miniwsa.application.alerting.AlertRule;
import com.akamai.miniwsa.application.alerting.FiringAlert;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /v1/alerts/evaluate}: the evaluation time and the rules firing now,
 * each with the observed count and the window it was measured over.
 */
public record AlertEvaluationResponse(Instant evaluatedAt, List<FiringAlertResponse> firing) {

    public static AlertEvaluationResponse from(AlertEvaluation evaluation) {
        List<FiringAlertResponse> firing = evaluation.firing().stream()
                .map(FiringAlertResponse::from)
                .toList();
        return new AlertEvaluationResponse(evaluation.evaluatedAt(), firing);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FiringAlertResponse(
            String ruleId,
            Long configId,
            RuleCategory category,
            int threshold,
            int windowMinutes,
            long actualCount,
            Instant windowFrom,
            Instant windowTo
    ) {

        public static FiringAlertResponse from(FiringAlert alert) {
            AlertRule rule = alert.rule();
            return new FiringAlertResponse(
                    rule.id(), rule.configId(), rule.category(), rule.threshold(),
                    rule.windowMinutes(), alert.actualCount(), alert.windowFrom(), alert.windowTo());
        }
    }
}
