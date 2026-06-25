package com.akamai.miniwsa.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Bound and validated query parameters for {@code GET /v1/stats/summary}. Validation lives
 * here as Bean Validation constraints, so a missing/invalid value is raised by the framework
 * and rendered by the single error handler — no throwing in the controller or service.
 */
public record SummaryParams(
        Long configId,
        @NotNull(message = "'from' is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @NotNull(message = "'to' is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
) {

    @AssertTrue(message = "'to' must be after 'from'")
    public boolean isRangeOrdered() {
        return from == null || to == null || to.isAfter(from);
    }
}
