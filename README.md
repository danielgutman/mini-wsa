# Mini WSA — Mini Web Security Analytics Pipeline

A simplified version of a Web Security Analytics (WSA) backend: it ingests security
event records (DLRs) over REST, classifies and enriches them (attack type + threat
score), stores them in ClickHouse, and exposes analytics APIs for statistics and
sample retrieval.

> Status: **Phase 1 — project skeleton.** The application boots and serves a health
> endpoint. Domain logic, ingestion, storage, and analytics APIs are added in later
> phases (see [Roadmap](#roadmap)).

## Tech stack

- Java 21, Spring Boot 3.3
- Maven (with wrapper)
- ClickHouse (column-oriented analytics store) via Docker Compose
- JUnit 5 + AssertJ for tests

## Build & run

Prerequisites: JDK 21. The Maven wrapper (`./mvnw`) handles Maven itself.

```bash
# Build and run the test suite
./mvnw verify

# Run the application
./mvnw spring-boot:run

# Verify it is up
curl http://localhost:8080/ping
# -> {"status":"ok","service":"mini-wsa"}

curl http://localhost:8080/actuator/health
# -> {"status":"UP",...}
```

## Storage

Storage is selected by `miniwsa.storage`:

- `memory` (default) — in-memory adapter; the app runs and is testable without any DB.
- `clickhouse` — persists to ClickHouse over JDBC.

### Local ClickHouse

```bash
docker compose up -d clickhouse
```

This starts a single-node ClickHouse and applies `src/main/resources/db/ClickHouseSchema.sql`
on first start (creating `mini_wsa.security_events`). Run the app against it with:

```bash
MINIWSA_STORAGE=clickhouse ./mvnw spring-boot:run
# connection defaults: jdbc:clickhouse://localhost:8123/mini_wsa, user/pass mini_wsa
```

The ClickHouse adapter is integration-tested with Testcontainers
(`ClickHouseEventRepositoryTest`); that test is skipped automatically when Docker is
unavailable and runs in CI.

## API

### Ingest events — `POST /v1/events/ingest`

Accepts a single event object or a JSON array. Returns `201 {"ingested": N}`; invalid
input returns `400` (RFC 7807 ProblemDetail with an `errors` array).

```bash
curl -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","timestamp":"2026-05-20T14:32:10Z","configId":14227,
       "clientIp":"203.0.113.42","path":"/api/v1/login","method":"POST","statusCode":403,
       "rule":{"id":"950001","severity":"CRITICAL","category":"INJECTION"},"action":"DENY"}'
```

### Summary statistics — `GET /v1/stats/summary`

Query params: `from`, `to` (required, ISO-8601), `configId` (optional — omit to aggregate
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

## Architecture

Clean / hexagonal layering — the domain stays free of Spring, HTTP, and JDBC:

```
Client / Generator
      |
      v
Spring REST Controllers      (api)
      |
      v
Application Services         (application)  --> Pure Domain Logic (domain)
      |                                           - AttackTypeClassifier
      v                                           - ThreatScoreCalculator
Repository Ports             (application/ports)
      |
      v
ClickHouse Adapter           (infrastructure)
      |
      v
ClickHouse
```

## Roadmap

| Phase | Scope | Tag |
|-------|-------|-----|
| 1 | Project skeleton, health, Docker Compose, CI | — |
| 2 | Domain model, enums, classifier, threat score | `v0.2-enrichment` |
| 3 | Ingestion API (single + batch, validation) | `v0.1-ingestion` |
| 4 | ClickHouse storage + repeat-offender query | `v0.3-storage` |
| 5 | Summary stats API | `v0.4-stats` |
| 6 | Samples API (filters + pagination) | `v0.5-samples` |
| 7 | Data generator (attack waves) | `v0.6-generator` |
| 8 | README polish, trade-offs | `v1.0-core-complete` |

## Storage choice

ClickHouse is chosen for the append-heavy, analytics-oriented workload (time-range
scans, top-N attackers/paths, grouped aggregations). A fuller justification and the
alternatives considered (PostgreSQL, Elasticsearch, Druid, Kafka) will be documented
as the storage layer lands in Phase 4.
