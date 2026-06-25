package com.akamai.miniwsa.application.query;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import java.time.Instant;

/**
 * Parameters for the samples query. All filters are optional ({@code null} means "no
 * filter"): {@code configId}, the half-open range {@code [from, to)}, {@code category},
 * and {@code action}. {@code limit}/{@code offset} are already normalized by the service
 * (limit in {@code [1, 100]}, offset {@code >= 0}).
 */
public record SamplesQuery(
        Long configId,
        Instant from,
        Instant to,
        RuleCategory category,
        Action action,
        int limit,
        int offset
) {
}
