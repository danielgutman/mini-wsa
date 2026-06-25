package com.akamai.miniwsa.application.ports;

import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;

/**
 * Read port for analytics queries. Backed by the active storage adapter (in-memory or
 * ClickHouse). Grows with the samples and time-series queries in later milestones.
 */
public interface EventQueryRepository {

    SummaryStats getSummary(SummaryQuery query);
}
