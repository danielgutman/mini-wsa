package com.akamai.miniwsa.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Structured error response returned by {@code ErrorHandlingAdvice}. {@code details}
 * is omitted from the JSON when absent (e.g. for 500s).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, List<FieldError> details) {

    public ApiError(String error, String message) {
        this(error, message, null);
    }

    /** A single field-level validation problem. */
    public record FieldError(String field, String message) {
    }
}
