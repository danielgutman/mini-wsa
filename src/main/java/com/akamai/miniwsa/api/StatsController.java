package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SummaryResponse;
import com.akamai.miniwsa.application.StatsService;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statistics endpoints. Thin: parses query params and delegates to {@link StatsService}.
 * {@code from}/{@code to} are required ISO-8601 instants; {@code configId} is optional.
 * Bad or missing params surface as 400s via the central error handler.
 */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(
            @RequestParam(required = false) Long configId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        SummaryStats stats = statsService.summary(new SummaryQuery(configId, from, to));
        return SummaryResponse.from(configId, from, to, stats);
    }
}
