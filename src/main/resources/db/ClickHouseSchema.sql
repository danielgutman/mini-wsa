-- Mini WSA storage schema.
--
-- One append-only table holds the enriched security events. ClickHouse's MergeTree
-- engine is column-oriented and well suited to the analytics workload: time-range
-- scans per config, top-N attacker/path queries, and grouped aggregations.
--
-- Design notes:
--   * timestamp    = event time (from the source DLR).
--   * received_at  = server-side ingestion time.
--   * LowCardinality(String) compresses repeated categorical values.
--   * ORDER BY supports time-range queries per config and attacker lookups.
CREATE TABLE IF NOT EXISTS mini_wsa.security_events
(
    event_id        String,
    timestamp       DateTime64(3, 'UTC'),
    received_at     DateTime64(3, 'UTC'),

    config_id       UInt64,
    policy_id       String,
    client_ip       String,
    hostname        String,
    path            String,
    method          LowCardinality(String),
    status_code     UInt16,
    user_agent      String,

    rule_id         String,
    rule_name       String,
    rule_message    String,
    rule_severity   LowCardinality(String),
    rule_category   LowCardinality(String),

    action          LowCardinality(String),

    geo_country     LowCardinality(String),
    geo_city        String,

    request_size    UInt64,
    response_size   UInt64,

    attack_type     LowCardinality(String),
    threat_score    UInt8
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (config_id, timestamp, client_ip, event_id);
