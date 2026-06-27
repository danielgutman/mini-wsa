package com.akamai.miniwsa.api.validation;

import com.akamai.miniwsa.config.LimitsProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * Spring-managed validator for {@link BatchSizeWithinLimit}. Being a bean lets it read the current
 * {@link LimitsProperties}, so the batch-size limit is configurable while validation remains
 * declarative (the violation is raised by the framework and mapped to a 400 by the central handler).
 */
@Component
public class BatchSizeValidator implements ConstraintValidator<BatchSizeWithinLimit, Collection<?>> {

    private final LimitsProperties limits;

    public BatchSizeValidator(LimitsProperties limits) {
        this.limits = limits;
    }

    @Override
    public boolean isValid(Collection<?> value, ConstraintValidatorContext context) {
        if (value == null || value.size() <= limits.maxBatchSize()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                "batch must not exceed " + limits.maxBatchSize() + " events").addConstraintViolation();
        return false;
    }
}
