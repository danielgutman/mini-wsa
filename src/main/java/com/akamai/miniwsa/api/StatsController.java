package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SummaryParams;
import com.akamai.miniwsa.api.dto.SummaryResponse;
import com.akamai.miniwsa.api.dto.TimeSeriesParams;
import com.akamai.miniwsa.api.dto.TimeSeriesResponse;
import com.akamai.miniwsa.application.StatsService;
import com.akamai.miniwsa.application.query.Interval;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.TimeSeriesBucket;
import com.akamai.miniwsa.application.query.TimeSeriesQuery;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statistics endpoints. Thin: binds {@code @Valid} query params and delegates to
 * {@link StatsService}. Missing/invalid params (required {@code from}/{@code to}, bad
 * timestamps, {@code to} not after {@code from}) are raised by Bean Validation and rendered
 * as 400s by the central error handler — the controller never throws.
 */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(@Valid SummaryParams params) {
        SummaryStats stats = statsService.summary(
                new SummaryQuery(params.configId(), params.from(), params.to()));
        return SummaryResponse.from(params.configId(), params.from(), params.to(), stats);
    }

    @GetMapping("/timeseries")
    public TimeSeriesResponse timeseries(@Valid TimeSeriesParams params) {
        Interval interval = Interval.fromCode(params.interval());
        List<TimeSeriesBucket> buckets = statsService.timeSeries(
                new TimeSeriesQuery(params.configId(), params.from(), params.to(), interval));
        return TimeSeriesResponse.from(params.configId(), interval.code(), buckets);
    }
}
