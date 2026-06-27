package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Bound and validated query parameters for {@code GET /v1/events/samples}. All filters are
 * optional; the constraints (pagination floors {@code limit >= 1}, {@code offset >= 0}, and a
 * valid time range) live here as Bean Validation annotations. The max-limit clamp and
 * defaulting are applied by the service.
 */
public record SamplesParams(
        Long configId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        RuleCategory category,
        Action action,
        @Min(value = 1, message = "limit must be >= 1") Integer limit,
        @Min(value = 0, message = "offset must be >= 0") Integer offset
) {

    @AssertTrue(message = "'to' must be after 'from'")
    @Schema(hidden = true) // validation predicate, not an input parameter
    public boolean isRangeOrdered() {
        return from == null || to == null || to.isAfter(from);
    }
}
