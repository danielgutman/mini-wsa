package com.akamai.miniwsa.api;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place where exceptions become HTTP responses (RFC 7807 {@link ProblemDetail}).
 * Controllers stay thin and never build error bodies; the framework raises validation
 * and parse exceptions, which propagate here. Stack traces are never leaked.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives standard ProblemDetail
 * handling for Spring MVC exceptions (e.g. unreadable bodies, unknown enum values) for
 * free; we override the validation cases to attach field-level {@code errors}.
 */
@RestControllerAdvice
public class ErrorHandlingAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingAdvice.class);

    /** Field-level validation problem exposed as a ProblemDetail {@code errors} entry. */
    public record FieldErrorDetail(String field, String message) {
    }

    /**
     * Validation of {@code @Valid} method parameters (our {@code List<@Valid ...>} body
     * and the {@code @NotEmpty} guard) surfaces as a {@link HandlerMethodValidationException}
     * in Spring 6.1+. Flatten its per-parameter results into field errors, indexing into
     * the batch (e.g. {@code [1].eventId}) where applicable.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        ex.getAllValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors paramErrors) {
                Integer index = paramErrors.getContainerIndex();
                String prefix = index == null ? "" : "[" + index + "].";
                paramErrors.getFieldErrors().forEach(fieldError ->
                        errors.add(new FieldErrorDetail(prefix + fieldError.getField(), message(fieldError.getDefaultMessage()))));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(resolvable ->
                        errors.add(new FieldErrorDetail(name == null ? "request" : name, message(resolvable.getDefaultMessage()))));
            }
        });

        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Validation of {@code @Valid} beans/param objects (request body, or bound query params
     * such as the stats/samples params). Includes field errors and class-level
     * ({@code @AssertTrue}) global errors, e.g. "'to' must be after 'from'".
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                errors.add(new FieldErrorDetail(fieldError.getField(), message(fieldError.getDefaultMessage()))));
        ex.getBindingResult().getGlobalErrors().forEach(globalError ->
                errors.add(new FieldErrorDetail(globalError.getObjectName(), message(globalError.getDefaultMessage()))));

        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Anything unexpected: log server-side, return a safe generic 500. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private static String message(String defaultMessage) {
        return defaultMessage == null ? "invalid" : defaultMessage;
    }
}
