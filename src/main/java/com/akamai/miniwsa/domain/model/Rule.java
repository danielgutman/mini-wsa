package com.akamai.miniwsa.domain.model;

import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;

/**
 * The security rule that matched an event, as reported by the source DLR.
 */
public record Rule(
        String id,
        String name,
        String message,
        Severity severity,
        RuleCategory category
) {
}
