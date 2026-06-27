package com.akamai.miniwsa.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a collection's size does not exceed the configured
 * {@code miniwsa.limits.max-batch-size}. Because the limit is runtime configuration, it can't be
 * a static {@code @Size} (annotation attributes must be compile-time constants); this constraint's
 * validator is a Spring bean that reads the current limit, so the check stays framework-raised and
 * is rendered by the single error handler — no throwing in the controller.
 */
@Documented
@Constraint(validatedBy = BatchSizeValidator.class)
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface BatchSizeWithinLimit {

    String message() default "batch exceeds the configured maximum size";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
