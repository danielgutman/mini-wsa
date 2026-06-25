package com.akamai.miniwsa.infrastructure.memory;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import com.akamai.miniwsa.application.ports.EventQueryRepository;
import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.application.query.SamplePage;
import com.akamai.miniwsa.application.query.SamplesQuery;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.SummaryStats.AttackerStats;
import com.akamai.miniwsa.application.query.SummaryStats.CategoryStats;
import com.akamai.miniwsa.application.query.SummaryStats.PathStats;
import com.akamai.miniwsa.application.query.TimeSeriesBucket;
import com.akamai.miniwsa.application.query.TimeSeriesQuery;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory adapter implementing the write/read/query ports. Lets the service run and be
 * integration-tested without a live ClickHouse; it is the default storage until the
 * ClickHouse adapter is selected via {@code miniwsa.storage}.
 */
@Repository
@ConditionalOnProperty(name = "miniwsa.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventRepository
        implements EventWriteRepository, EventReadRepository, EventQueryRepository {

    private static final int TOP_N = 10;

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

    @Override
    public SummaryStats getSummary(SummaryQuery query) {
        List<EnrichedSecurityEvent> matching = store.stream()
                .filter(enriched -> matches(enriched, query))
                .toList();

        Map<String, CategoryStats> byCategory = new LinkedHashMap<>();
        matching.stream()
                .collect(groupingBy(e -> e.event().rule().category().name()))
                .forEach((category, events) ->
                        byCategory.put(category, new CategoryStats(events.size(), avgThreatScore(events))));

        Map<String, Long> byAction = matching.stream()
                .collect(groupingBy(e -> e.event().action().name(), counting()));

        List<AttackerStats> topAttackers = matching.stream()
                .collect(groupingBy(e -> e.event().clientIp()))
                .entrySet().stream()
                .map(entry -> new AttackerStats(entry.getKey(), entry.getValue().size(), avgThreatScore(entry.getValue())))
                .sorted(Comparator.comparingLong(AttackerStats::count).reversed())
                .limit(TOP_N)
                .toList();

        List<PathStats> topTargetedPaths = matching.stream()
                .collect(groupingBy(e -> e.event().path(), counting()))
                .entrySet().stream()
                .map(entry -> new PathStats(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(PathStats::count).reversed())
                .limit(TOP_N)
                .toList();

        return new SummaryStats(matching.size(), byCategory, byAction, topAttackers, topTargetedPaths);
    }

    @Override
    public SamplePage getSamples(SamplesQuery query) {
        List<EnrichedSecurityEvent> filtered = store.stream()
                .filter(enriched -> matches(enriched, query))
                .sorted(Comparator.comparing((EnrichedSecurityEvent e) -> e.event().timestamp()).reversed())
                .toList();

        int fromIndex = Math.min(query.offset(), filtered.size());
        int toIndex = Math.min(fromIndex + query.limit(), filtered.size());
        List<EnrichedSecurityEvent> page = List.copyOf(filtered.subList(fromIndex, toIndex));

        return new SamplePage(filtered.size(), query.limit(), query.offset(), page);
    }

    @Override
    public List<TimeSeriesBucket> getTimeSeries(TimeSeriesQuery query) {
        long stepSeconds = query.interval().duration().toSeconds();

        List<Instant> timestamps = store.stream()
                .filter(enriched -> inRange(enriched, query.configId(), query.from(), query.to()))
                .map(enriched -> enriched.event().timestamp())
                .toList();

        List<TimeSeriesBucket> buckets = new ArrayList<>();
        for (Instant start = floorToInterval(query.from(), stepSeconds);
                start.isBefore(query.to());
                start = start.plusSeconds(stepSeconds)) {
            Instant end = start.plusSeconds(stepSeconds);
            final Instant bucketStart = start;
            long count = timestamps.stream()
                    .filter(timestamp -> !timestamp.isBefore(bucketStart) && timestamp.isBefore(end))
                    .count();
            buckets.add(new TimeSeriesBucket(bucketStart, end, count));
        }
        return buckets;
    }

    private static boolean inRange(EnrichedSecurityEvent enriched, Long configId, Instant from, Instant to) {
        SecurityEvent event = enriched.event();
        if (configId != null && configId != event.configId()) {
            return false;
        }
        Instant timestamp = event.timestamp();
        return !timestamp.isBefore(from) && timestamp.isBefore(to);
    }

    private static Instant floorToInterval(Instant instant, long stepSeconds) {
        long aligned = Math.floorDiv(instant.getEpochSecond(), stepSeconds) * stepSeconds;
        return Instant.ofEpochSecond(aligned);
    }

    private static boolean matches(EnrichedSecurityEvent enriched, SamplesQuery query) {
        SecurityEvent event = enriched.event();
        if (query.configId() != null && query.configId() != event.configId()) {
            return false;
        }
        Instant timestamp = event.timestamp();
        if (query.from() != null && timestamp.isBefore(query.from())) {
            return false;
        }
        if (query.to() != null && !timestamp.isBefore(query.to())) {
            return false;
        }
        if (query.category() != null && event.rule().category() != query.category()) {
            return false;
        }
        return query.action() == null || event.action() == query.action();
    }

    private static boolean matches(EnrichedSecurityEvent enriched, SummaryQuery query) {
        Instant timestamp = enriched.event().timestamp();
        if (timestamp.isBefore(query.from()) || !timestamp.isBefore(query.to())) {
            return false;
        }
        return query.configId() == null || query.configId() == enriched.event().configId();
    }

    private static double avgThreatScore(List<EnrichedSecurityEvent> events) {
        double avg = events.stream().mapToInt(EnrichedSecurityEvent::threatScore).average().orElse(0);
        return Math.round(avg * 10) / 10.0;
    }

    /** Snapshot of stored events, for tests and (temporarily) local inspection. */
    public List<EnrichedSecurityEvent> findAll() {
        return List.copyOf(store);
    }
}
