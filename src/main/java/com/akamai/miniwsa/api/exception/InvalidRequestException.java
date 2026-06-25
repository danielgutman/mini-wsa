package com.akamai.miniwsa.api.exception;

import com.akamai.miniwsa.api.dto.ApiError;
import java.util.List;

/**
 * Thrown when an incoming request is structurally or semantically invalid
 * (missing required fields, malformed JSON, unknown enum values). Carries
 * optional field-level details that the central error handler renders as a 400.
 */
public class InvalidRequestException extends RuntimeException {

    private final transient List<ApiError.FieldError> details;

    public InvalidRequestException(String message) {
        this(message, List.of());
    }

    public InvalidRequestException(String message, List<ApiError.FieldError> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<ApiError.FieldError> details() {
        return details;
    }
}
