package com.akamai.miniwsa.application;

import com.akamai.miniwsa.application.ports.EventQueryRepository;
import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.application.query.SamplesQuery;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the samples query: normalizes and validates pagination
 * (limit default 20, max 100; offset default 0), validates the optional time range,
 * then delegates retrieval to the {@link EventQueryRepository}.
 */
@Service
public class SamplesService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final EventQueryRepository queryRepository;

    public SamplesService(EventQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    public SamplePage samples(Long configId, Instant from, Instant to,
                              RuleCategory category, Action action,
                              Integer limit, Integer offset) {
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        int effectiveOffset = offset == null ? 0 : offset;

        if (effectiveLimit < 1 || effectiveOffset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be >= 1 and offset >= 0");
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must be after 'from'");
        }
        effectiveLimit = Math.min(effectiveLimit, MAX_LIMIT);

        return queryRepository.getSamples(
                new SamplesQuery(configId, from, to, category, action, effectiveLimit, effectiveOffset));
    }
}
