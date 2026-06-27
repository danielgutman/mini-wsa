package com.akamai.miniwsa.infrastructure.clickhouse;

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
import com.akamai.miniwsa.config.LimitsProperties;
import com.akamai.miniwsa.domain.enums.Action;
import com.akamai.miniwsa.domain.enums.RuleCategory;
import com.akamai.miniwsa.domain.enums.Severity;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.GeoLocation;
import com.akamai.miniwsa.domain.model.Rule;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse {@link EventWriteRepository}: batch-inserts enriched events into the
 * {@code security_events} MergeTree table. Active only when
 * {@code miniwsa.storage=clickhouse}; the in-memory adapter is the default otherwise.
 *
 * <p>This is the only place that talks to the database (IO policy §5.7). Inserts use a
 * single multi-row {@code batchUpdate}, which suits ClickHouse's append-heavy, batch-
 * oriented write model.
 */
@Repository
@ConditionalOnProperty(name = "miniwsa.storage", havingValue = "clickhouse")
public class ClickHouseEventRepository
        implements EventWriteRepository, EventReadRepository, EventQueryRepository {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseEventRepository.class);

    private static final String INSERT_SQL = """
            INSERT INTO security_events (
                event_id, timestamp, received_at,
                config_id, policy_id, client_ip, hostname, path, method, status_code, user_agent,
                rule_id, rule_name, rule_message, rule_severity, rule_category,
                action, geo_country, geo_city, request_size, response_size,
                attack_type, threat_score
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LimitsProperties limits;

    public ClickHouseEventRepository(JdbcTemplate clickHouseJdbcTemplate, LimitsProperties limits) {
        this.jdbcTemplate = clickHouseJdbcTemplate;
        this.limits = limits;
    }

    @Override
    public void saveAll(List<EnrichedSecurityEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                bind(ps, events.get(i));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
        log.debug("Persisted {} event(s) to ClickHouse", events.size());
    }

    @Override
    public Map<String, List<Instant>> findEventTimestampsByClientIp(
            Collection<String> clientIps, Instant fromInclusive, Instant toExclusive) {
        if (clientIps.isEmpty()) {
            return Map.of();
        }
        List<String> ips = List.copyOf(clientIps);
        String placeholders = String.join(",", Collections.nCopies(ips.size(), "?"));
        String sql = "SELECT client_ip, timestamp FROM security_events WHERE client_ip IN ("
                + placeholders + ") AND timestamp >= ? AND timestamp < ?";

        Object[] params = new Object[ips.size() + 2];
        for (int i = 0; i < ips.size(); i++) {
            params[i] = ips.get(i);
        }
        params[ips.size()] = utc(fromInclusive);
        params[ips.size() + 1] = utc(toExclusive);

        Map<String, List<Instant>> timestampsByIp = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            timestampsByIp.computeIfAbsent(rs.getString("client_ip"), key -> new ArrayList<>())
                    .add(toInstant(rs, "timestamp"));
        }, params);
        return timestampsByIp;
    }

    @Override
    public SummaryStats getSummary(SummaryQuery query) {
        Filter filter = rangeFilter(query.configId(), query.from(), query.to());

        // Every event has exactly one category, so the per-category counts sum to the total —
        // derive it here instead of issuing a separate count() query.
        Map<String, CategoryStats> byCategory = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT rule_category, count() AS c, avg(threat_score) AS avg "
                        + "FROM security_events " + filter.where + " GROUP BY rule_category",
                rs -> {
                    byCategory.put(rs.getString("rule_category"),
                            new CategoryStats(rs.getLong("c"), round(rs.getDouble("avg"))));
                },
                filter.params);

        long total = byCategory.values().stream().mapToLong(CategoryStats::count).sum();

        Map<String, Long> byAction = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT action, count() AS c FROM security_events " + filter.where + " GROUP BY action",
                rs -> {
                    byAction.put(rs.getString("action"), rs.getLong("c"));
                },
                filter.params);

        List<AttackerStats> topAttackers = jdbcTemplate.query(
                "SELECT client_ip, count() AS c, avg(threat_score) AS avg "
                        + "FROM security_events " + filter.where
                        + " GROUP BY client_ip ORDER BY c DESC LIMIT " + limits.summaryTopLimit(),
                (rs, i) -> new AttackerStats(rs.getString("client_ip"), rs.getLong("c"), round(rs.getDouble("avg"))),
                filter.params);

        List<PathStats> topTargetedPaths = jdbcTemplate.query(
                "SELECT path, count() AS c FROM security_events " + filter.where
                        + " GROUP BY path ORDER BY c DESC LIMIT " + limits.summaryTopLimit(),
                (rs, i) -> new PathStats(rs.getString("path"), rs.getLong("c")),
                filter.params);

        return new SummaryStats(total, byCategory, byAction, topAttackers, topTargetedPaths);
    }

    @Override
    public SamplePage getSamples(SamplesQuery query) {
        Filter filter = filterFor(query);

        Long total = jdbcTemplate.queryForObject(
                "SELECT count() FROM security_events " + filter.where, Long.class, filter.params);

        List<Object> pagedParams = new ArrayList<>(List.of(filter.params));
        pagedParams.add(query.limit());
        pagedParams.add(query.offset());

        List<EnrichedSecurityEvent> items = jdbcTemplate.query(
                "SELECT * FROM security_events " + filter.where + " ORDER BY timestamp DESC LIMIT ? OFFSET ?",
                SAMPLE_ROW_MAPPER, pagedParams.toArray());

        return new SamplePage(total == null ? 0L : total, query.limit(), query.offset(), items);
    }

    @Override
    public List<TimeSeriesBucket> getTimeSeries(TimeSeriesQuery query) {
        long stepSeconds = query.interval().duration().toSeconds();
        Filter filter = rangeFilter(query.configId(), query.from(), query.to());

        Map<Instant, Long> countsByBucket = new HashMap<>();
        jdbcTemplate.query(
                "SELECT toStartOfInterval(timestamp, INTERVAL " + stepSeconds + " second) AS bucket, count() AS c "
                        + "FROM security_events " + filter.where + " GROUP BY bucket",
                rs -> {
                    countsByBucket.put(toInstant(rs, "bucket"), rs.getLong("c"));
                },
                filter.params);

        // Build a contiguous, interval-aligned series, filling empty buckets with 0. The last
        // bucket's end is clamped to the requested `to` when `to` is not interval-aligned, so a
        // bucket label never overshoots the queried range.
        List<TimeSeriesBucket> buckets = new ArrayList<>();
        for (Instant start = floorToInterval(query.from(), stepSeconds);
                start.isBefore(query.to());
                start = start.plusSeconds(stepSeconds)) {
            Instant rawEnd = start.plusSeconds(stepSeconds);
            Instant end = rawEnd.isAfter(query.to()) ? query.to() : rawEnd;
            buckets.add(new TimeSeriesBucket(start, end, countsByBucket.getOrDefault(start, 0L)));
        }
        return buckets;
    }

    @Override
    public long countByCategory(Long configId, RuleCategory category, Instant fromInclusive, Instant toExclusive) {
        StringBuilder where = new StringBuilder("WHERE rule_category = ? AND timestamp >= ? AND timestamp < ?");
        List<Object> params = new ArrayList<>();
        params.add(category.name());
        params.add(utc(fromInclusive));
        params.add(utc(toExclusive));
        if (configId != null) {
            where.append(" AND config_id = ?");
            params.add(configId);
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM security_events " + where, Long.class, params.toArray());
        return count == null ? 0L : count;
    }

    /** Builds the shared WHERE clause and positional parameters for the time range (+ optional config). */
    private static Filter rangeFilter(Long configId, Instant from, Instant to) {
        StringBuilder where = new StringBuilder("WHERE timestamp >= ? AND timestamp < ?");
        List<Object> params = new ArrayList<>();
        params.add(utc(from));
        params.add(utc(to));
        if (configId != null) {
            where.append(" AND config_id = ?");
            params.add(configId);
        }
        return new Filter(where.toString(), params.toArray());
    }

    private static Instant floorToInterval(Instant instant, long stepSeconds) {
        long aligned = Math.floorDiv(instant.getEpochSecond(), stepSeconds) * stepSeconds;
        return Instant.ofEpochSecond(aligned);
    }

    /** Builds the WHERE clause for samples, where every filter (including the range) is optional. */
    private static Filter filterFor(SamplesQuery query) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (query.configId() != null) {
            conditions.add("config_id = ?");
            params.add(query.configId());
        }
        if (query.from() != null) {
            conditions.add("timestamp >= ?");
            params.add(utc(query.from()));
        }
        if (query.to() != null) {
            conditions.add("timestamp < ?");
            params.add(utc(query.to()));
        }
        if (query.category() != null) {
            conditions.add("rule_category = ?");
            params.add(query.category().name());
        }
        if (query.action() != null) {
            conditions.add("action = ?");
            params.add(query.action().name());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return new Filter(where, params.toArray());
    }

    private record Filter(String where, Object[] params) {
    }

    private static final RowMapper<EnrichedSecurityEvent> SAMPLE_ROW_MAPPER = (rs, rowNum) -> {
        Rule rule = new Rule(
                rs.getString("rule_id"),
                rs.getString("rule_name"),
                rs.getString("rule_message"),
                Severity.valueOf(rs.getString("rule_severity")),
                RuleCategory.valueOf(rs.getString("rule_category")));
        GeoLocation geo = new GeoLocation(rs.getString("geo_country"), rs.getString("geo_city"));
        SecurityEvent event = new SecurityEvent(
                rs.getString("event_id"),
                toInstant(rs, "timestamp"),
                rs.getLong("config_id"),
                rs.getString("policy_id"),
                rs.getString("client_ip"),
                rs.getString("hostname"),
                rs.getString("path"),
                rs.getString("method"),
                rs.getInt("status_code"),
                rs.getString("user_agent"),
                rule,
                Action.valueOf(rs.getString("action")),
                geo,
                rs.getLong("request_size"),
                rs.getLong("response_size"));
        return new EnrichedSecurityEvent(
                event, rs.getString("attack_type"), rs.getInt("threat_score"), toInstant(rs, "received_at"));
    };

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static void bind(PreparedStatement ps, EnrichedSecurityEvent enriched) throws SQLException {
        SecurityEvent event = enriched.event();
        GeoLocation geo = event.geoLocation();

        ps.setString(1, event.eventId());
        ps.setObject(2, utc(event.timestamp()));
        ps.setObject(3, utc(enriched.receivedAt()));
        ps.setLong(4, event.configId());
        ps.setString(5, nullToEmpty(event.policyId()));
        ps.setString(6, nullToEmpty(event.clientIp()));
        ps.setString(7, nullToEmpty(event.hostname()));
        ps.setString(8, nullToEmpty(event.path()));
        ps.setString(9, nullToEmpty(event.method()));
        ps.setInt(10, event.statusCode());
        ps.setString(11, nullToEmpty(event.userAgent()));
        ps.setString(12, nullToEmpty(event.rule().id()));
        ps.setString(13, nullToEmpty(event.rule().name()));
        ps.setString(14, nullToEmpty(event.rule().message()));
        ps.setString(15, event.rule().severity().name());
        ps.setString(16, event.rule().category().name());
        ps.setString(17, event.action().name());
        ps.setString(18, geo == null ? "" : nullToEmpty(geo.country()));
        ps.setString(19, geo == null ? "" : nullToEmpty(geo.city()));
        ps.setLong(20, event.requestSize());
        ps.setLong(21, event.responseSize());
        ps.setString(22, nullToEmpty(enriched.attackType()));
        ps.setInt(23, enriched.threatScore());
    }

    /** ClickHouse DateTime64 is stored in UTC; pass a UTC LocalDateTime. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
