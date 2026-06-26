# Mini WSA — Mini Web Security Analytics Pipeline

A simplified Web Security Analytics (WSA) backend: it ingests security event records
(DLRs) over REST, validates and **enriches** them (human-readable attack type + a 0–100
threat score + a server-side `receivedAt`), stores them efficiently in **ClickHouse**, and
exposes analytics APIs for statistics and individual sample retrieval.

## Tech stack

- **Java 21**, **Spring Boot 3.3**
- **Maven** (via the `./mvnw` wrapper)
- **ClickHouse** (column-oriented analytics store) over JDBC; **Docker Compose** for local dev
- **JUnit 5 + AssertJ**; **Testcontainers** for the ClickHouse adapter
- GitHub Actions: unit CI on every push, a release-gated full-pipeline E2E

## Architecture

Clean / hexagonal layering — the **domain is pure** (no Spring, HTTP, JSON, or JDBC). The
`application` layer orchestrates use cases through **ports** (interfaces); `infrastructure`
provides the adapters; `api` is thin (controllers + DTOs, with one central error handler).

```
            Client / Generator
                   │  HTTP (JSON)
                   ▼
   api          Controllers ──► ErrorHandlingAdvice (RFC 7807 ProblemDetail)
                   │  DTOs ⇄ domain
                   ▼
   application  EventIngestionService · StatsService · SamplesService
                   │            │
                   │            └─► Pure domain logic (domain/service)
                   │                  • AttackTypeClassifier
                   │                  • ThreatScoreCalculator
                   ▼
   ports        EventWriteRepository · EventReadRepository · EventQueryRepository · ClockProvider
                   ▲ (selected by miniwsa.storage)
   infrastructure  ├─ ClickHouseEventRepository (JDBC)      ─► ClickHouse
                   └─ InMemoryEventRepository (default)
```

**Ingestion flow:** validate request (Bean Validation) → `EventIngestionService` stamps
`receivedAt` from `ClockProvider`, classifies the attack type, asks `EventReadRepository`
for the repeat-offender count, computes the threat score with the **pure**
`ThreatScoreCalculator`, then persists via `EventWriteRepository`.

**Query flow:** controllers bind `@Valid` params → `StatsService`/`SamplesService` →
`EventQueryRepository` → the active adapter (ClickHouse `GROUP BY`/top-N, or in-memory streams).

### Enrichment rules

| `rule.category` | `attackType` |
|---|---|
| INJECTION | SQL/Command Injection |
| XSS | Cross-Site Scripting |
| PROTOCOL_VIOLATION | Protocol Anomaly |
| DATA_LEAKAGE | Data Exfiltration |
| BOT | Bot Activity |
| DOS | Denial of Service |
| RATE_LIMIT | Rate Limiting |

**Threat score** (integer, capped at 100): severity base (CRITICAL 40 / HIGH 30 / MEDIUM 20
/ LOW 10) + action (DENY +20 / ALERT +10 / MONITOR 0) + `/admin` or `/login` in path (+15) +
repeat offender (+15, when **> 5** events from the same `clientIp` exist in the prior 10 min).

## Storage choice

This is an append-heavy analytics workload: ingest many events, then run time-range scans,
top-N (attackers/paths), and grouped aggregations. **ClickHouse** fits well — it is
column-oriented, compresses repeated categorical fields with `LowCardinality`, and is fast
for exactly these scans/aggregations. The table is `MergeTree`, `PARTITION BY
toYYYYMM(timestamp)`, `ORDER BY (config_id, timestamp, client_ip, event_id)` so the common
"per config, over a time range, by attacker" access pattern hits the primary index.

Alternatives considered:

- **PostgreSQL** — reliable and simple, but row-oriented; less ideal for very large
  analytical scans/aggregations at scale.
- **Elasticsearch / OpenSearch** — excellent search/filtering, but heavier operationally and
  less focused on numeric aggregations.
- **Apache Druid** — strong real-time analytics, but operationally too heavy for a take-home.
- **Kafka + stream processor** — the right shape for production ingestion, but outside the
  (corrected) REST-only scope here.

A default **in-memory adapter** is provided so the app runs and is testable without any DB;
storage is chosen at runtime via `miniwsa.storage` (`memory` | `clickhouse`).

## Build & run

Prerequisites: **JDK 21** (the `./mvnw` wrapper handles Maven). Docker is optional (needed
only for ClickHouse and the Testcontainers/E2E tests).

```bash
# Build + run the full test suite
./mvnw verify

# Run the app (default in-memory storage — no DB needed)
./mvnw spring-boot:run

curl http://localhost:8080/ping              # {"status":"ok","service":"mini-wsa"}
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
```

### Running against ClickHouse

```bash
# Start a local ClickHouse (applies src/main/resources/db/ClickHouseSchema.sql on first run,
# creating mini_wsa.security_events)
docker compose up -d clickhouse

# Point the app at it
MINIWSA_STORAGE=clickhouse ./mvnw spring-boot:run
# defaults: jdbc:clickhouse://localhost:8123/mini_wsa, user/pass mini_wsa
# overridable via CLICKHOUSE_URL / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD

docker compose down -v   # stop + wipe data
```

## API documentation

### Ingest events — `POST /v1/events/ingest`

Accepts a single event object **or** a JSON array. Returns `201 {"ingested": N}`. Invalid
input returns `400` as an RFC 7807 `ProblemDetail` with an `errors` array (per-field, with
batch indices like `[1].rule`).

```bash
curl -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","timestamp":"2026-05-20T14:32:10Z","configId":14227,
       "clientIp":"203.0.113.42","path":"/api/v1/login","method":"POST","statusCode":403,
       "rule":{"id":"950001","severity":"CRITICAL","category":"INJECTION"},"action":"DENY"}'
# -> 201 {"ingested":1}
```

### Summary statistics — `GET /v1/stats/summary`

Params: `from`, `to` (**required**, ISO-8601), `configId` (optional — omit to aggregate
across all configs). Returns totals, per-category (count + avg threat score), per-action
counts, and the top-10 attackers and targeted paths.

```bash
curl "http://localhost:8080/v1/stats/summary?configId=14227\
&from=2026-05-20T00:00:00Z&to=2026-05-21T00:00:00Z"
```

```json
{
  "configId": 14227,
  "timeRange": { "from": "2026-05-20T00:00:00Z", "to": "2026-05-21T00:00:00Z" },
  "totalEvents": 3,
  "byCategory": { "INJECTION": { "count": 2, "avgThreatScore": 75.0 } },
  "byAction": { "DENY": 2, "MONITOR": 1 },
  "topAttackers": [ { "clientIp": "203.0.113.42", "count": 2, "avgThreatScore": 75.0 } ],
  "topTargetedPaths": [ { "path": "/login", "count": 2 } ]
}
```

### Sample events — `GET /v1/events/samples`

Returns individual enriched events, **newest first**, with a `total` for pagination. All
filters optional: `configId`, `from`/`to`, `category`, `action`. Pagination: `limit`
(default 20, max 100), `offset` (default 0). Invalid pagination/range → `400`.

```bash
curl "http://localhost:8080/v1/events/samples?configId=14227&category=INJECTION&limit=20&offset=0"
```

```json
{
  "total": 3,
  "limit": 20,
  "offset": 0,
  "items": [
    {
      "eventId": "q2", "timestamp": "2026-05-20T10:05:00Z", "configId": 14227,
      "clientIp": "203.0.113.42", "path": "/login",
      "rule": { "severity": "HIGH", "category": "INJECTION" },
      "action": "DENY", "attackType": "SQL/Command Injection",
      "threatScore": 65, "receivedAt": "2026-05-20T10:05:01Z"
    }
  ]
}
```

### Time series — `GET /v1/stats/timeseries`

Event counts bucketed by `interval` (`1m` | `5m` | `1h`) over `[from, to)`, ready to plot.
`from`/`to`/`interval` are required; `configId` optional. Buckets are contiguous and
interval-aligned (empty buckets reported with count 0); a bad `interval` → `400`.

```bash
curl "http://localhost:8080/v1/stats/timeseries?configId=14227\
&from=2026-05-20T00:00:00Z&to=2026-05-20T01:00:00Z&interval=5m"
```

```json
{
  "configId": 14227,
  "interval": "5m",
  "buckets": [
    { "from": "2026-05-20T00:00:00Z", "to": "2026-05-20T00:05:00Z", "count": 42 },
    { "from": "2026-05-20T00:05:00Z", "to": "2026-05-20T00:10:00Z", "count": 0 }
  ]
}
```

## Generating test data

A **seeded** generator produces realistic events plus **attack waves** (bursts from one IP
on one sensitive path within 3 minutes) — useful for exercising top attackers/paths, the
repeat-offender bonus, and category/action distributions. A fixed `--seed` makes output
reproducible (the seed is printed, so any dataset/bug can be reproduced).

```bash
./mvnw -q compile exec:java -Dexec.args="--count 10000 --output generated-events.json"

curl -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" --data @generated-events.json
```

Options (all optional): `--count`, `--output`, `--seed`, `--config-id`, `--waves`, `--wave-size`.

## Testing

```bash
./mvnw test          # unit + integration tests
```

What runs:

- **Unit** — pure logic: `AttackTypeClassifierTest`, `ThreatScoreCalculatorTest`,
  `EventIngestionServiceTest` (fakes for clock/repos), `SecurityEventGeneratorTest`.
- **API integration** — `@SpringBootTest` + MockMvc over the in-memory store, ingesting
  through the real endpoints: `EventIngestionControllerTest`, `StatsControllerTest`,
  `SamplesControllerTest`. All assert against **real inserted data** (no mocking).
- **ClickHouse adapter** — `ClickHouseEventRepositoryTest` uses **Testcontainers** (real
  ClickHouse). It is **skipped automatically when Docker is unavailable** and runs in CI.

### End-to-end test (10k+ events, ClickHouse)

`FullPipelineE2ETest` generates 10k+ events, ingests them through the HTTP API into a
production-like ClickHouse, then drives every endpoint and asserts consistent results
(totals add up, waves surface as top attackers, samples paginate/order correctly, and the
summary/samples counts agree). It is **CI-only** (guarded by `E2E_ENABLED`) and gates
releases. To run it locally:

```bash
docker compose up -d clickhouse
E2E_ENABLED=true MINIWSA_STORAGE=clickhouse \
CLICKHOUSE_URL=jdbc:clickhouse://localhost:8123/mini_wsa \
CLICKHOUSE_USER=mini_wsa CLICKHOUSE_PASSWORD=mini_wsa \
  ./mvnw -Dtest=FullPipelineE2ETest test
docker compose down -v
```

## Releasing

Versions come from `pom.xml`. Developer **milestones** are
manual `vX.Y-something` tags (e.g. `v0.3-stats`) and trigger nothing. A **release** is the
`Release` workflow (`workflow_dispatch`): it runs the full-pipeline E2E gate, and **only on
success** reads the pom version and creates the `vX.Y.Z` tag + GitHub Release. Cut `v1.0.0`
by setting `<version>1.0.0</version>` in `pom.xml`, then running the workflow.

## Trade-offs

- **Repeat-offender is one query per event.** For a batch of N events, that's N count
  queries — simple and correct, but O(N) round-trips. Fine for this scope; see improvements.
- **Within-batch events don't count toward repeat-offender** (they're persisted after the
  batch is enriched). A documented simplification.
- **No `event_id` idempotency** — re-ingesting the same event inserts a duplicate. `MergeTree`
  does not dedupe. Acceptable here; flagged as a production gap.
- **Errors are RFC 7807 `ProblemDetail`**, not the brief's suggested `{error,message,details}`
  shape — ProblemDetail is the Spring Boot 3 standard and is handled in exactly one place.
- **REST-only ingestion** (no Kafka), per the corrected scope.

## What I would improve with more time

- **Batch the repeat-offender computation**: one grouped query per ingest batch (count by IP
  over the window) instead of per-event; or a Redis rolling counter / ClickHouse materialized
  view for production scale.
- **Idempotency**: dedupe on `event_id` (e.g. `ReplacingMergeTree` or a pre-insert check).
- **Schema migrations** (Flyway-style) instead of a Docker init script; managed by the app.
- **Keyset pagination** for samples (large `offset` scans rows it then discards).
- **The time-series bonus** (`GET /v1/stats/timeseries`), reusing the same query layer.
- **Production concerns**: authentication, rate limiting, metrics/tracing, async/bulk
  ingestion, ClickHouse replication/sharding.

## Known limitations

- Duplicate events are not deduplicated.
- Repeat-offender lookups are O(N) per batch (see trade-offs).
- The in-memory adapter is not persistent (dev/test only).
- Docker Compose runs a single-node ClickHouse (no replication/sharding).
- The threat-score cap (100) is currently unreachable — the max reachable score is 90;
  the cap is kept as defensive logic.
- A single ingest request is capped at 10,000 events (beyond that → 400); clients should
  chunk larger loads. The array is still fully deserialized before the size check, so a
  hard request-body-size limit would be the next step for untrusted input.

## Milestones

| Milestone | Scope | Tag |
|---|---|---|
| Ingestion | `POST /v1/events/ingest` (single + batch, validation, `receivedAt`) | `v0.1-ingestion` |
| Enrichment | attack type + threat score + repeat offender | `v0.2-enrichment` |
| Storage | ClickHouse adapter | — |
| Stats | summary API | `v0.3-stats` |
| Samples | samples API (filters + pagination) | `v0.4-sample` |
| Generator | data generator + release-gated E2E | `v0.5-generator` |
| Release | first semver release | `v1.0.0` |

## Notes on AI assistance

AI tooling (Claude Code) was used to help scaffold and iterate. Every part of the codebase
was reviewed and is intended to be explainable: the architecture, the pure/effectful split,
the enrichment math, and the storage queries.
