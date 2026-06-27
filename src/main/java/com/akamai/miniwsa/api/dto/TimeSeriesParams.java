package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.application.query.Interval;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Bound and validated query parameters for {@code GET /v1/stats/timeseries}. {@code from},
 * {@code to}, and {@code interval} are required and must form a valid range; {@code configId}
 * is optional. The constraints live here as Bean Validation annotations.
 */
public record TimeSeriesParams(
        Long configId,
        @NotNull(message = "'from' is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @NotNull(message = "'to' is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @NotNull(message = "'interval' is required")
        @Pattern(regexp = Interval.PATTERN, message = "interval must be one of 1m, 5m, 1h") String interval
) {

    @AssertTrue(message = "'to' must be after 'from'")
    public boolean isRangeOrdered() {
        return from == null || to == null || to.isAfter(from);
    }
}
