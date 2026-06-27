# Mini WSA — Mini Web Security Analytics Pipeline

A simplified Web Security Analytics (WSA) backend: it ingests security event records
(DLRs) over REST, validates and **enriches** them (human-readable attack type + a 0–100
threat score + a server-side `receivedAt`), stores them efficiently in **ClickHouse**, and
exposes analytics APIs for statistics and individual sample retrieval.

## Tech stack

- **Java 21**, **Spring Boot 3.3**
- **Maven** (via the `./mvnw` wrapper)
- **ClickHouse** (column-oriented analytics store) over JDBC; **Docker Compose** for local dev
- **Redis** (optional) for persistent, shared alert-rule storage
- **OpenAPI 3 / Swagger UI** (springdoc) for interactive, code-generated API docs
- **Micrometer + Prometheus** for metrics (`/actuator/prometheus`)
- **JUnit 5 + AssertJ**; **Testcontainers** for the ClickHouse and Redis adapters
- GitHub Actions: build + tests and a Docker-stack smoke test on every push/PR; a release-gated
  full-pipeline E2E

## Architecture

Clean / hexagonal layering — the **domain is pure** (no Spring, HTTP, JSON, or JDBC). The
`application` layer orchestrates use cases through **ports** (interfaces); `infrastructure`
provides the adapters; `api` is thin (controllers + DTOs, with one central error handler).


```
            Client / Generator
                   │  HTTP (JSON)
                   ▼
   edge         nginx (optional) — request-size limit (413), reverse proxy
                   │
                   ▼
   api          Controllers ──► ErrorHandlingAdvice (RFC 7807 ProblemDetail)
                   │  DTOs ⇄ domain
                   ▼
   application  EventIngestionService · StatsService · SamplesService · AlertService
                   │            │
                   │            └─► Pure domain logic (domain/service)
                   │                  • AttackTypeClassifier
                   │                  • ThreatScoreCalculator
                   ▼
   ports        EventWriteRepository · EventReadRepository · EventQueryRepository
                 · AlertRuleRepository · ClockProvider
                   ▲ (events: miniwsa.storage · rules: miniwsa.alerts.storage)
   infrastructure  events ├─ ClickHouseEventRepository (JDBC)        ─► ClickHouse
                          └─ InMemoryEventRepository (default)
                   rules  ├─ RedisAlertRuleRepository (JSON)         ─► Redis
                          └─ InMemoryAlertRuleRepository (default)
```

**Ingestion flow:** validate request (Bean Validation) → `EventIngestionService` stamps
`receivedAt` from `ClockProvider`, classifies the attack type, asks `EventReadRepository`
for the repeat-offender count, computes the threat score with the **pure**
`ThreatScoreCalculator`, then persists via `EventWriteRepository`.

**Query flow:** controllers bind `@Valid` params → `StatsService`/`SamplesService` →
`EventQueryRepository` → the active adapter (ClickHouse `GROUP BY`/top-N, or in-memory streams).

**Alerting flow (bonus):** `AlertService` stores threshold rules (`AlertRuleRepository`) and, on
`evaluate`, counts each rule's category over `[now − window, now)` (`ClockProvider` + the same
`EventQueryRepository`) — reusing the existing ports rather than adding a new pipeline.

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

**Alert rules** (the alerting bonus) are a different shape of data — small, mutable, config-like
state, not append-heavy events — so they use a separate store, selected by `miniwsa.alerts.storage`
(`memory` | `redis`). The default is in-memory; **Redis** persists rules across restarts and shares
them across app instances (stored as a hash of JSON rules). ClickHouse is deliberately *not* reused
here: it is an append-only analytics engine, poor at the small point reads/writes that rule
management needs — a relational DB or Redis is the right fit.

## Build & run

Prerequisites: **JDK 21** (the `./mvnw` wrapper handles Maven). Docker is optional (needed
only for ClickHouse/Redis and the Testcontainers/E2E tests).

```bash
# Build + run the full test suite
./mvnw verify

# Run the app (default in-memory storage — no DB needed)
./mvnw spring-boot:run

curl http://localhost:8080/ping              # {"status":"ok","service":"mini-wsa"}
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
```

### Full stack with Docker (app + ClickHouse + Redis + edge + Prometheus)

Brings up everything — ClickHouse, **Redis** (the app runs with `miniwsa.alerts.storage=redis`, so
alert rules persist), the app (in ClickHouse mode), an **nginx edge proxy** that enforces the
request-size limit, and a **Prometheus** that scrapes the app — with one command:

```bash
docker compose up --build           # nginx :8080 → app → ClickHouse, + Prometheus :9090
curl http://localhost:8080/ping     # through the edge
open http://localhost:9090          # Prometheus UI (scrapes the app's metrics)
docker compose down -v              # stop + wipe data
```

The stack also runs **Prometheus** (`http://localhost:9090`), which scrapes the app directly at
`app:8080/actuator/prometheus` every 5s (config in `docker/prometheus.yml`). That `app` hostname
only resolves inside the Compose network — from your host you read the same metrics through the
edge on `localhost:8080` (see [Observability](#observability) below).

Traffic flows `client → nginx (:8080) → app (:8080) → clickhouse (:8123)`. The edge caps
request bodies (`client_max_body_size 8m`) and returns **413** for oversized payloads before
they reach the app — defense-in-depth alongside the app's configurable batch cap
(`miniwsa.limits.max-batch-size`, a JSON **400**). ClickHouse's schema is auto-applied on first start.

### App-only against ClickHouse (local dev)

```bash
docker compose up -d clickhouse     # just the events DB (alert rules stay in-memory)
MINIWSA_STORAGE=clickhouse ./mvnw spring-boot:run
# defaults: jdbc:clickhouse://localhost:8123/mini_wsa, user/pass mini_wsa
# overridable via CLICKHOUSE_URL / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD

# To persist alert rules too, also start Redis and select it:
#   docker compose up -d clickhouse redis
#   MINIWSA_STORAGE=clickhouse MINIWSA_ALERTS_STORAGE=redis REDIS_HOST=localhost ./mvnw spring-boot:run
docker compose down -v
```

## Observability

Metrics are exposed in Prometheus format at `GET /actuator/prometheus` (Micrometer). Spring Boot
already provides HTTP request rate/latency (`http_server_requests_seconds*`) and JVM/system meters
out of the box; on top of those, the ingestion pipeline registers a few domain-specific signals:

| Metric | Type | What it tells you |
|---|---|---|
| `miniwsa_ingest_events_total` | counter | events accepted and enriched |
| `miniwsa_ingest_repeat_offenders_events_total` | counter | how many were flagged as repeat offenders |
| `miniwsa_ingest_batch_size_events` | summary | request batch sizes (count/sum/max) |
| `miniwsa_threat_score` | summary | distribution of assigned threat scores |

```bash
curl -s http://localhost:8080/actuator/prometheus | grep '^miniwsa_'
```

Wire this endpoint into a Prometheus scrape job and the signals drive dashboards/alerts (e.g. a
spike in repeat offenders or in the threat-score average).

## API documentation

Interactive docs are generated from the code (springdoc/OpenAPI 3):

- **Swagger UI** — `http://localhost:8080/swagger-ui.html` (try the endpoints in the browser)
- **OpenAPI spec** — `http://localhost:8080/v3/api-docs` (JSON, for client codegen / import)

The reference below mirrors that spec.

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
counts, and the top attackers and targeted paths (default 10 — see [Configuration](#configuration)).

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

### Alert rules — `POST /v1/alerts/define` and `GET /v1/alerts/evaluate` (bonus)

Define threshold rules, then check which are firing. A rule means *"more than `threshold` events
of `category` within the last `windowMinutes` minutes"* (`configId` optional — null = all configs).
`evaluate` counts each rule's category over `[now − windowMinutes, now)` and returns the rules
whose count exceeds the threshold. Rules are kept **in memory** by default; set
`miniwsa.alerts.storage=redis` to persist them across restarts and share them across instances
(same port, a different adapter).

```bash
# define a rule
curl -X POST http://localhost:8080/v1/alerts/define \
  -H "Content-Type: application/json" \
  -d '{"configId":14227,"category":"INJECTION","threshold":100,"windowMinutes":5}'
# -> 201 {"id":"rule-1","configId":14227,"category":"INJECTION","threshold":100,"windowMinutes":5}

# evaluate all defined rules against current data
curl http://localhost:8080/v1/alerts/evaluate
```

```json
{
  "evaluatedAt": "2026-05-20T14:35:00Z",
  "firing": [
    {
      "ruleId": "rule-1", "configId": 14227, "category": "INJECTION",
      "threshold": 100, "windowMinutes": 5, "actualCount": 143,
      "windowFrom": "2026-05-20T14:30:00Z", "windowTo": "2026-05-20T14:35:00Z"
    }
  ]
}
```

The window is relative to **now**, so only recent events fire a rule. Invalid definitions
(missing `category`, `threshold`/`windowMinutes` < 1) → `400`.

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

- **Unit** — pure logic and services over the real in-memory adapters with a fixed clock:
  `AttackTypeClassifierTest`, `ThreatScoreCalculatorTest`, `EventIngestionServiceTest`,
  `AlertServiceTest`, `SecurityEventGeneratorTest`.
- **API integration** — `@SpringBootTest` + MockMvc over the in-memory store, ingesting
  through the real endpoints: `EventIngestionControllerTest`, `StatsControllerTest`,
  `SamplesControllerTest`, `AlertControllerTest`. All assert against **real inserted data** (no mocking).
- **Adapter integration** — `ClickHouseEventRepositoryTest` (real ClickHouse) and
  `RedisAlertRuleRepositoryTest` (real Redis) use **Testcontainers**. They are **skipped
  automatically when Docker is unavailable** and run in CI.

### End-to-end test (10k+ events; ClickHouse + Redis)

`FullPipelineE2ETest` generates 10k+ events, ingests them through the HTTP API into a
production-like ClickHouse, then drives every endpoint and asserts consistent results
(totals add up, waves surface as top attackers, samples paginate/order correctly, the
summary/samples counts agree, and an alert rule fires). It exercises **both stores together** —
alert rules in **Redis**, event counts from **ClickHouse**. It is **CI-only** (guarded by
`E2E_ENABLED`) and gates releases. To run it locally:

```bash
docker compose up -d clickhouse redis
E2E_ENABLED=true MINIWSA_STORAGE=clickhouse \
CLICKHOUSE_URL=jdbc:clickhouse://localhost:8123/mini_wsa \
CLICKHOUSE_USER=mini_wsa CLICKHOUSE_PASSWORD=mini_wsa \
MINIWSA_ALERTS_STORAGE=redis REDIS_HOST=localhost \
  ./mvnw -Dtest=FullPipelineE2ETest test
docker compose down -v
```

(The alerting test also passes with the default in-memory rule store — drop the `MINIWSA_ALERTS_STORAGE`/`REDIS_HOST`
lines and the `redis` service to run without Redis.)

## Releasing

Versions come from `pom.xml`. Developer **milestones** are manual `vX.Y-something` tags (e.g.
`v0.3-stats`) and trigger nothing. A **release** is the `Release` workflow, run manually from the
**Actions** tab (`workflow_dispatch`) — you don't push the tag yourself. It reads the pom version
(rejecting `-SNAPSHOT`), runs the full-pipeline E2E gate, and **only on success** builds + pushes
the versioned container image to **GHCR** and creates the `vX.Y.Z` tag + GitHub Release. Cut
`v1.0.0` by setting `<version>1.0.0</version>` in `pom.xml`, then running the workflow.

Each release publishes:

```
ghcr.io/danielgutman/mini-wsa:1.0.0     # the released version
ghcr.io/danielgutman/mini-wsa:latest    # also moved to the newest release
```

### Run a released version

The Compose `app` service defaults to a local build but accepts a published image via
`MINIWSA_IMAGE`, so you can run the **full stack on a specific version** without building anything:

```bash
export MINIWSA_IMAGE=ghcr.io/danielgutman/mini-wsa:1.0.0
docker compose pull app          # fetch the published image
docker compose up -d             # ClickHouse + this app version + edge + Prometheus
docker compose down -v
```

Or run just the app container against your own ClickHouse:

```bash
docker run -p 8080:8080 \
  -e MINIWSA_STORAGE=clickhouse \
  -e CLICKHOUSE_URL=jdbc:clickhouse://<host>:8123/mini_wsa \
  -e CLICKHOUSE_USER=mini_wsa -e CLICKHOUSE_PASSWORD=mini_wsa \
  ghcr.io/danielgutman/mini-wsa:1.0.0
```

This single-container run keeps alert rules **in-memory**; to persist them, add
`-e MINIWSA_ALERTS_STORAGE=redis -e REDIS_HOST=<host>` pointing at a reachable Redis.

> GHCR packages are **private** by default. To `docker pull` without authenticating, make the
> package public in the repo's **Packages** settings; otherwise run `docker login ghcr.io` first.

## Trade-offs

- **Repeat-offender history is one query per batch**, then the per-event 10-minute windowed
  count runs in memory — so a large batch is **not** N+1 on the database. The trade-off is the
  in-memory count; a ClickHouse-side windowed aggregate or a Redis rolling counter would scale
  further (see roadmap).
- **Within-batch events don't count toward repeat-offender** (they're persisted after the
  batch is enriched). A documented simplification.
- **No `event_id` idempotency** — re-ingesting the same event inserts a duplicate. `MergeTree`
  does not dedupe. Acceptable here; flagged as a production gap.
- **Errors are RFC 7807 `ProblemDetail`**, not the brief's suggested `{error,message,details}`
  shape — ProblemDetail is the Spring Boot 3 standard and is handled in exactly one place.
- **REST-only ingestion** (no Kafka), per the corrected scope.

## Roadmap (with more time)

The core pipeline is complete, plus several extras — time-series stats, alerting (both bonus
challenges), configurable limits, Prometheus metrics, OpenAPI/Swagger, and the full Docker stack.
The next steps, in rough priority order:

**Security**
- **Authentication & authorization** — protect ingest and query (API key / mTLS, or OAuth2 at the
  edge). The APIs are currently open; for a security product this is the most defensible gap.
- **Rate limiting / quotas** — per-client throttling at the edge or app.

**Resilience & scale**
- **Survive a ClickHouse outage** — retry plus a bounded write buffer / dead-letter and
  backpressure; today an outage means `500`s and dropped events.
- **Async / buffered ingestion** — decouple accept from persist (in-app batch-and-flush, or a
  Kafka front) for higher sustained throughput.
- **High availability** — the app tier is near-stateless, so HA there is just running N replicas
  behind the LB; the heavy part is the data tier: ClickHouse replication + sharding via
  clickhouse-keeper and `Distributed` tables.

**Data & API**
- **Idempotency / dedup** — dedupe on `event_id` (`ReplacingMergeTree`, or a pre-insert check).
- **Keyset pagination** for samples (a large `offset` scans rows it then discards).
- **Richer analytics** — geo and per-rule breakdowns, severity-over-time (the columns are already
  stored).
- **Schema migrations** (Flyway-style, app-managed) instead of the Docker init script.

**Observability**
- **Grafana dashboards + Prometheus alert rules** on top of the metrics already exposed.
- **Distributed tracing** (OpenTelemetry) to complement the metrics.

## Configuration

Operational limits are externalized under `miniwsa.limits` (the analytics/scoring rules are
deliberately fixed in code, not configurable):

| Setting | Env var | Default | Meaning |
|---|---|---|---|
| `miniwsa.limits.max-batch-size` | `MINIWSA_MAX_BATCH_SIZE` | 10000 | max events per ingest request (larger → 400) |
| `miniwsa.limits.summary-top-limit` | `MINIWSA_SUMMARY_TOP_LIMIT` | 10 | summary entries for top attackers / top paths |

Override per environment via env vars or a k8s ConfigMap; to apply a change with **no service
downtime**, update the value and do a **rolling restart** (`kubectl rollout restart`) — pods
cycle one at a time. (No custom config API: that's overkill for deploy-time limits.)

**Storage selectors** (each picks an adapter behind a port, via `@ConditionalOnProperty`):

| Setting | Env var | Default | Options |
|---|---|---|---|
| `miniwsa.storage` | `MINIWSA_STORAGE` | `memory` | `memory` \| `clickhouse` (events) |
| `miniwsa.alerts.storage` | `MINIWSA_ALERTS_STORAGE` | `memory` | `memory` \| `redis` (alert rules) |

The Redis connection is `spring.data.redis.host`/`port` (env `REDIS_HOST` / `REDIS_PORT`); the
client connects lazily, so the default in-memory profile boots with no Redis running.

## Known limitations

- Duplicate events are not deduplicated.
- Repeat-offender counts are computed in memory from one query per batch (see trade-offs).
- The in-memory adapter is not persistent (dev/test only).
- Alert rules are not deduplicated. The default store is in-memory (lost on restart); set
  `miniwsa.alerts.storage=redis` to persist them (the Docker stack does this).
- Docker Compose runs a single-node ClickHouse (no replication/sharding).
- The threat-score cap (100) is currently unreachable — the max reachable score is 90;
  the cap is kept as defensive logic.
- A single ingest request is capped at `miniwsa.limits.max-batch-size` (default 10,000) events
  (beyond that → app returns 400); clients should chunk larger loads. The app-level check
  deserializes the array first, so the byte-level guard is the **edge** (`docker compose` runs
  nginx with `client_max_body_size`, returning 413 before the app reads the body); in production
  that limit lives at the gateway/Akamai edge.

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
