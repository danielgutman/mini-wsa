package com.akamai.miniwsa.application;

import com.akamai.miniwsa.application.ports.EventQueryRepository;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.TimeSeriesBucket;
import com.akamai.miniwsa.application.query.TimeSeriesQuery;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the summary query. Request validation is handled at the API boundary (Bean
 * Validation on the params), so this layer just delegates to the {@link EventQueryRepository};
 * the heavy aggregation work happens in the storage adapter.
 */
@Service
public class StatsService {

    private final EventQueryRepository queryRepository;

    public StatsService(EventQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    public SummaryStats summary(SummaryQuery query) {
        return queryRepository.getSummary(query);
    }

    public List<TimeSeriesBucket> timeSeries(TimeSeriesQuery query) {
        return queryRepository.getTimeSeries(query);
    }
}
