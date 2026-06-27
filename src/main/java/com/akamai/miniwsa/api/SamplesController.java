package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SamplesParams;
import com.akamai.miniwsa.api.dto.SamplesResponse;
import com.akamai.miniwsa.application.SamplesService;
import com.akamai.miniwsa.application.query.SamplePage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Samples endpoint: returns individual enriched events matching the optional filters
 * (configId, time range, category, action), newest first and paginated (limit/offset),
 * by binding the query params and delegating to {@link SamplesService}.
 */
@RestController
@RequestMapping("/v1/events")
@Tag(name = "Samples", description = "Retrieve individual enriched events")
public class SamplesController {

    private final SamplesService samplesService;

    public SamplesController(SamplesService samplesService) {
        this.samplesService = samplesService;
    }

    @GetMapping("/samples")
    @Operation(summary = "List enriched events (filtered, newest first, paginated)")
    public SamplesResponse samples(@Valid @ParameterObject SamplesParams params) {
        SamplePage page = samplesService.samples(
                params.configId(), params.from(), params.to(),
                params.category(), params.action(), params.limit(), params.offset());
        return SamplesResponse.from(page);
    }
}
