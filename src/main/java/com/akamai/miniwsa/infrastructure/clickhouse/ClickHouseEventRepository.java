package com.akamai.miniwsa.infrastructure.clickhouse;

import com.akamai.miniwsa.application.ports.EventQueryRepository;
import com.akamai.miniwsa.application.ports.EventReadRepository;
import com.akamai.miniwsa.application.ports.EventWriteRepository;
import com.akamai.miniwsa.application.query.SummaryQuery;
import com.akamai.miniwsa.application.query.SummaryStats;
import com.akamai.miniwsa.application.query.SummaryStats.AttackerStats;
import com.akamai.miniwsa.application.query.SummaryStats.CategoryStats;
import com.akamai.miniwsa.application.query.SummaryStats.PathStats;
import com.akamai.miniwsa.domain.model.EnrichedSecurityEvent;
import com.akamai.miniwsa.domain.model.GeoLocation;
import com.akamai.miniwsa.domain.model.SecurityEvent;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final int TOP_N = 10;

    private static final String COUNT_BY_IP_SQL =
            "SELECT count() FROM security_events WHERE client_ip = ? AND timestamp >= ? AND timestamp < ?";

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

    public ClickHouseEventRepository(JdbcTemplate clickHouseJdbcTemplate) {
        this.jdbcTemplate = clickHouseJdbcTemplate;
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
    public long countByClientIpBetween(String clientIp, Instant fromInclusive, Instant toExclusive) {
        Long count = jdbcTemplate.queryForObject(
                COUNT_BY_IP_SQL, Long.class, clientIp, utc(fromInclusive), utc(toExclusive));
        return count == null ? 0L : count;
    }

    @Override
    public SummaryStats getSummary(SummaryQuery query) {
        Filter filter = filterFor(query);

        Long total = jdbcTemplate.queryForObject(
                "SELECT count() FROM security_events " + filter.where, Long.class, filter.params);

        Map<String, CategoryStats> byCategory = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT rule_category, count() AS c, avg(threat_score) AS avg "
                        + "FROM security_events " + filter.where + " GROUP BY rule_category",
                rs -> {
                    byCategory.put(rs.getString("rule_category"),
                            new CategoryStats(rs.getLong("c"), round(rs.getDouble("avg"))));
                },
                filter.params);

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
                        + " GROUP BY client_ip ORDER BY c DESC LIMIT " + TOP_N,
                (rs, i) -> new AttackerStats(rs.getString("client_ip"), rs.getLong("c"), round(rs.getDouble("avg"))),
                filter.params);

        List<PathStats> topTargetedPaths = jdbcTemplate.query(
                "SELECT path, count() AS c FROM security_events " + filter.where
                        + " GROUP BY path ORDER BY c DESC LIMIT " + TOP_N,
                (rs, i) -> new PathStats(rs.getString("path"), rs.getLong("c")),
                filter.params);

        return new SummaryStats(total == null ? 0L : total, byCategory, byAction, topAttackers, topTargetedPaths);
    }

    /** Builds the shared WHERE clause and positional parameters for the time range (+ optional config). */
    private static Filter filterFor(SummaryQuery query) {
        StringBuilder where = new StringBuilder("WHERE timestamp >= ? AND timestamp < ?");
        List<Object> params = new ArrayList<>();
        params.add(utc(query.from()));
        params.add(utc(query.to()));
        if (query.configId() != null) {
            where.append(" AND config_id = ?");
            params.add(query.configId());
        }
        return new Filter(where.toString(), params.toArray());
    }

    private record Filter(String where, Object[] params) {
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
