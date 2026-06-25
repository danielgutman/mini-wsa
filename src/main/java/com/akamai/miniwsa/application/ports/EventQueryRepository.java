package com.akamai.miniwsa.application.ports;

import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.application.query.SamplesQuery;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.TimeSeriesBucket;
import com.akamai.miniwsa.application.query.TimeSeriesQuery;
import java.util.List;

/**
 * Read port for analytics queries. Backed by the active storage adapter (in-memory or
 * ClickHouse).
 */
public interface EventQueryRepository {

    SummaryStats getSummary(SummaryQuery query);

    SamplePage getSamples(SamplesQuery query);

    List<TimeSeriesBucket> getTimeSeries(TimeSeriesQuery query);
}
