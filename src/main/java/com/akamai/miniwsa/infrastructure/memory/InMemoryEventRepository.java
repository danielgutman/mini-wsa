package com.akamai.miniwsa.infrastructure.memory;

import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link EventWriteRepository}/{@link EventReadRepository} adapter. Lets the
 * service run and be integration-tested without a live ClickHouse; it is the default
 * storage until the ClickHouse adapter is selected via {@code miniwsa.storage}.
 */
@Repository
@ConditionalOnProperty(name = "miniwsa.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventRepository implements EventWriteRepository, EventReadRepository {

    private final List<EnrichedSecurityEvent> store = new CopyOnWriteArrayList<>();

    @Override
    public void saveAll(List<EnrichedSecurityEvent> events) {
        store.addAll(events);
    }

    @Override
    public long countByClientIpBetween(String clientIp, Instant fromInclusive, Instant toExclusive) {
        return store.stream()
                .filter(enriched -> enriched.event().clientIp().equals(clientIp))
                .map(enriched -> enriched.event().timestamp())
                .filter(timestamp -> !timestamp.isBefore(fromInclusive) && timestamp.isBefore(toExclusive))
                .count();
    }

    /** Snapshot of stored events, for tests and (temporarily) local inspection. */
    public List<EnrichedSecurityEvent> findAll() {
        return List.copyOf(store);
    }
}
