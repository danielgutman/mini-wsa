package com.akamai.miniwsa.application.ports;

import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import java.util.List;

/**
 * Output port for persisting enriched events. The application layer depends on
 * this interface; the concrete adapter (in-memory now, ClickHouse later) lives in
 * {@code infrastructure}. Batch-oriented to match append-heavy ingestion.
 */
public interface EventWriteRepository {
    void saveAll(List<EnrichedSecurityEvent> events);
}
