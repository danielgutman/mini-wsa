package com.akamai.miniwsa.application;

import com.akamai.miniwsa.application.ports.EventQueryRepository;
import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.application.query.SamplesQuery;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the samples query. Pagination/range validation is handled at the API
 * boundary (Bean Validation on the params); this layer only applies the pagination
 * defaults and the max-limit clamp (pure transformations, no throwing) before delegating
 * to the {@link EventQueryRepository}.
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
        int effectiveLimit = Math.min(limit == null ? DEFAULT_LIMIT : limit, MAX_LIMIT);
        int effectiveOffset = offset == null ? 0 : offset;

        return queryRepository.getSamples(
                new SamplesQuery(configId, from, to, category, action, effectiveLimit, effectiveOffset));
    }
}
