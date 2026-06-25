package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.ApiError;
import com.akamai.miniwsa.api.exception.InvalidRequestException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Single place where exceptions become HTTP responses. Controllers stay thin and
 * never build ad-hoc error bodies. Stack traces are never leaked to clients.
 */
@RestControllerAdvice
public class ErrorHandlingAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingAdvice.class);

    /** Validation / semantic problems we raise explicitly. */
    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleInvalidRequest(InvalidRequestException ex) {
        List<ApiError.FieldError> details = ex.details().isEmpty() ? null : ex.details();
        return new ApiError("VALIDATION_ERROR", ex.getMessage(), details);
    }

    /** Body that cannot be parsed at all (bad JSON, wrong types at the root). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleUnreadable(HttpMessageNotReadableException ex) {
        return new ApiError("VALIDATION_ERROR", "Malformed request body");
    }

    /** Anything unexpected: log server-side, return a safe generic message. */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return new ApiError("INTERNAL_ERROR", "An unexpected error occurred");
    }
}
