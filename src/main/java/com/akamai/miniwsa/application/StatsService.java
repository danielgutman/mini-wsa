package com.akamai.miniwsa.application;

import com.akamai.miniwsa.application.ports.EventQueryRepository;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates analytics queries. Validates the requested time range, then delegates
 * to the {@link EventQueryRepository}; the heavy aggregation work happens in the
 * storage adapter (the database is good at it).
 */
@Service
public class StatsService {

    private final EventQueryRepository queryRepository;

    public StatsService(EventQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    public SummaryStats summary(SummaryQuery query) {
        if (!query.to().isAfter(query.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must be after 'from'");
        }
        return queryRepository.getSummary(query);
    }
}
