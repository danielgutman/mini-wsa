package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.application.query.TimeSeriesBucket;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Response body for {@code GET /v1/stats/timeseries}: the echoed config and interval, plus
 * the contiguous, interval-aligned buckets (each with a count) ready to plot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimeSeriesResponse(
        Long configId,
        String interval,
        List<TimeSeriesBucket> buckets
) {

    public static TimeSeriesResponse from(Long configId, String interval, List<TimeSeriesBucket> buckets) {
        return new TimeSeriesResponse(configId, interval, buckets);
    }
}
