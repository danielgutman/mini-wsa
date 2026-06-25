package com.akamai.miniwsa.infrastructure.memory;

import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link EventWriteRepository} adapter. Lets the service run and be
 * integration-tested without a live ClickHouse; it is the default storage until
 * the ClickHouse adapter is wired in (selected via {@code miniwsa.storage}).
 *
 * <p>Storage-only for now; read/query methods are added alongside enrichment and
 * the analytics APIs.
 */
@Repository
@ConditionalOnProperty(name = "miniwsa.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventRepository implements EventWriteRepository {

    private final List<EnrichedSecurityEvent> store = new CopyOnWriteArrayList<>();

    @Override
    public void saveAll(List<EnrichedSecurityEvent> events) {
        store.addAll(events);
    }

    /** Snapshot of stored events, for tests and (temporarily) local inspection. */
    public List<EnrichedSecurityEvent> findAll() {
        return List.copyOf(store);
    }
}
