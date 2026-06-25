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
 * Samples endpoint. Thin: binds {@code @Valid} query params and delegates to
 * {@link SamplesService}. Invalid filters/pagination are raised by Bean Validation and
 * rendered as 400s by the central error handler — the controller never throws.
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
