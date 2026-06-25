package com.akamai.miniwsa.application.ports;

import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.application.query.SamplesQuery;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;

/**
 * Read port for analytics queries. Backed by the active storage adapter (in-memory or
 * ClickHouse). Grows with the time-series query in a later milestone.
 */
public interface EventQueryRepository {

    SummaryStats getSummary(SummaryQuery query);

    SamplePage getSamples(SamplesQuery query);
}
