package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SamplesParams;
import com.akamai.miniwsa.api.dto.SamplesResponse;
import com.akamai.miniwsa.application.SamplesService;
import com.akamai.miniwsa.application.query.SamplePage;
import jakarta.validation.Valid;
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
public class SamplesController {

    private final SamplesService samplesService;

    public SamplesController(SamplesService samplesService) {
        this.samplesService = samplesService;
    }

    @GetMapping("/samples")
    public SamplesResponse samples(@Valid SamplesParams params) {
        SamplePage page = samplesService.samples(
                params.configId(), params.from(), params.to(),
                params.category(), params.action(), params.limit(), params.offset());
        return SamplesResponse.from(page);
    }
}
