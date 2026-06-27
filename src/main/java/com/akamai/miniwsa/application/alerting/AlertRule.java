package com.akamai.miniwsa.application.alerting;

import com.akamai.miniwsa.domain.enums.RuleCategory;

/**
 * A defined alert rule: "more than {@code threshold} events of {@code category} within the last
 * {@code windowMinutes} minutes (for {@code configId}, or any config when null) → trigger".
 *
 * @param id           assigned by the store on {@code define} (null before saving)
 * @param configId     optional scope; null evaluates across all configurations
 * @param category     the rule category to count
 * @param threshold    fire when the count is strictly greater than this
 * @param windowMinutes the look-back window, ending at evaluation time
 */
public record AlertRule(
        String id,
        Long configId,
        RuleCategory category,
        int threshold,
        int windowMinutes
) {
}
