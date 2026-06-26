package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * Request-side representation of a single incoming security event (DLR).
 *
 * <p>Carries all JSON/validation concerns so the domain stays pure. The required
 * fields below define the ingestion schema; optional fields (policyId, hostname,
 * userAgent, geoLocation, request/response sizes) may be omitted. {@code timestamp}
 * is bound as an {@link Instant}, so a malformed value fails JSON parsing and is
 * surfaced as a 400.
 */
public record IngestEventRequest(
        @NotBlank String eventId,
        @NotNull Instant timestamp,
        @NotNull @PositiveOrZero Long configId,
        String policyId,
        @NotBlank String clientIp,
        String hostname,
        @NotBlank String path,
        @NotBlank String method,
        @NotNull @Min(100) @Max(599) Integer statusCode,
        String userAgent,
        @NotNull @Valid RuleDto rule,
        @NotNull Action action,
        @Valid GeoLocationDto geoLocation,
        @PositiveOrZero Long requestSize,
        @PositiveOrZero Long responseSize
) {

    /**
     * Maps this validated request to the pure domain model. Optional numeric
     * sizes default to {@code 0}; an absent geoLocation maps to {@code null}.
     */
    public SecurityEvent toDomain() {
        return new SecurityEvent(
                eventId,
                timestamp,
                configId,
                policyId,
                clientIp,
                hostname,
                path,
                method,
                statusCode,
                userAgent,
                rule.toDomain(),
                action,
                geoLocation == null ? null : geoLocation.toDomain(),
                requestSize == null ? 0L : requestSize,
                responseSize == null ? 0L : responseSize
        );
    }
}
