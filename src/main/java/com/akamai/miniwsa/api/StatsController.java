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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statistics endpoints: a summary of aggregates (totals, per-category/action counts, top
 * attackers/paths) and a time-series of event counts bucketed over an interval, for an optional
 * config and a time range — binding the query params and delegating to {@link StatsService}.
 */
@RestController
@RequestMapping("/v1/stats")
@Tag(name = "Statistics", description = "Aggregated and time-series analytics")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Summary aggregates over a config and time range")
    public SummaryResponse summary(@Valid SummaryParams params) {
        SummaryStats stats = statsService.summary(
                new SummaryQuery(params.configId(), params.from(), params.to()));
        return SummaryResponse.from(params.configId(), params.from(), params.to(), stats);
    }

    @GetMapping("/timeseries")
    @Operation(summary = "Event counts bucketed by interval over a time range")
    public TimeSeriesResponse timeseries(@Valid TimeSeriesParams params) {
        Interval interval = Interval.fromCode(params.interval());
        List<TimeSeriesBucket> buckets = statsService.timeSeries(
                new TimeSeriesQuery(params.configId(), params.from(), params.to(), interval));
        return TimeSeriesResponse.from(params.configId(), interval.code(), buckets);
    }
}
