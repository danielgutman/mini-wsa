package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SamplesResponse;
import com.akamai.miniwsa.application.SamplesService;
import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Samples endpoint. Thin: parses optional filters/pagination and delegates to
 * {@link SamplesService}. All params are optional; invalid enums, timestamps, or
 * pagination values surface as 400s via the central error handler.
 */
@RestController
@RequestMapping("/v1/events")
public class SamplesController {

    private final SamplesService samplesService;

    public SamplesController(SamplesService samplesService) {
        this.samplesService = samplesService;
    }

    @GetMapping("/samples")
    public SamplesResponse samples(
            @RequestParam(required = false) Long configId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) RuleCategory category,
            @RequestParam(required = false) Action action,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        SamplePage page = samplesService.samples(configId, from, to, category, action, limit, offset);
        return SamplesResponse.from(page);
    }
}
