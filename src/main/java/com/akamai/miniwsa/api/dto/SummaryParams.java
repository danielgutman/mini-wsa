package com.akamai.miniwsa.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Bound and validated query parameters for {@code GET /v1/stats/summary}. {@code from} and
 * {@code to} are required and must form a valid range; {@code configId} is optional. The
 * constraints live here as Bean Validation annotations.
 */
public record SummaryParams(
        Long configId,
        @NotNull(message = "'from' is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @NotNull(message = "'to' is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
) {

    @AssertTrue(message = "'to' must be after 'from'")
    @Schema(hidden = true) // validation predicate, not an input parameter
    public boolean isRangeOrdered() {
        return from == null || to == null || to.isAfter(from);
    }
}
