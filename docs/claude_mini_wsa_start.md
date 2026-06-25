# Claude Starting Brief — Akamai CSI Mini WSA Assignment

## 0. Source of Truth

The uploaded PDF is the PRD for this project.

Build a backend service called **Mini WSA** — a simplified security analytics pipeline.

The goal is to ingest security events, validate them, enrich them, store them efficiently, and expose REST APIs for analytics and samples.

Do not over-engineer. Focus on working software, clean design, small commits, tests, and clear README documentation.

---

## 1. PRD Summary

Mini WSA should support:

1. Ingesting security events through a REST API.
2. Validating incoming events.
3. Classifying each event into a human-readable attack type.
4. Computing a threat score from 0 to 100.
5. Assigning a server-side `receivedAt` timestamp.
6. Persisting the enriched event.
7. Exposing statistics APIs.
8. Exposing sample-event APIs with filters and pagination.
9. Providing a data generator that creates realistic security events and attack waves.
10. Providing tests and clear build/run documentation.

Core APIs:

```http
POST /v1/events/ingest
GET  /v1/stats/summary
GET  /v1/events/samples
```

Optional bonus, only after the core is complete:

```http
GET /v1/stats/timeseries
```

Do not implement Kafka. The correct PRD requires REST ingestion only.

---

## 2. Product Behavior in Simple Terms

The service receives raw security logs, such as SQL injection attempts, bot activity, XSS attempts, DoS events, and rate-limit events.

For each event:

1. Validate the request.
2. Convert `rule.category` into a readable `attackType`.
3. Calculate `threatScore`.
4. Add `receivedAt`.
5. Store the enriched event.

Then users can query:

- How many events happened in a time range?
- Which categories were most common?
- Which actions were most common?
- Which IPs attacked the most?
- Which paths were targeted the most?
- Show me individual matching events with pagination.

---

## 3. Technical Stack

Use:

- Java 21
- Spring Boot 3.x
- Maven
- ClickHouse as the main storage engine
- JDBC / `NamedParameterJdbcTemplate` or Spring `JdbcClient`
- Docker Compose for local ClickHouse
- JUnit 5
- AssertJ
- Spring Boot integration tests
- Testcontainers if simple enough; otherwise use an in-memory repository adapter for API integration tests
- Should be a CI on the repo each commit that run the unit tests

### Why ClickHouse?

This assignment is an analytics pipeline over append-heavy security events.

ClickHouse is a strong fit because:

- It is column-oriented.
- It handles high-volume append-only event data well.
- It is efficient for time-range scans, aggregations, top-N queries, and grouped analytics.
- It has good compression for repeated fields like category, action, country, hostname, and path.
- It better matches high-scale analytics workloads than a traditional OLTP database.

For the README, mention alternatives:

- PostgreSQL: easier and very reliable, but less ideal for very large analytical scans.
- Elasticsearch/OpenSearch: strong search/filtering, but heavier operationally and less focused on numeric aggregations.
- Apache Druid: strong real-time analytics, but too heavy for this take-home assignment.
- Kafka + stream processors: relevant for production ingestion, but outside the corrected PRD scope.

---

## 4. High-Level Design

Use a simple Clean Architecture / Hexagonal Architecture style.

Keep business logic pure and isolated.

Suggested package structure:

```text
src/main/java/com/example/miniwsa
  api/
    EventIngestionController.java
    StatsController.java
    SamplesController.java
    ErrorHandlingAdvice.java
    dto/
  application/
    EventIngestionService.java
    StatsService.java
    SamplesService.java
    ports/
      EventWriteRepository.java
      EventReadRepository.java
      EventQueryRepository.java
      ClockProvider.java
  domain/
    model/
      SecurityEvent.java
      EnrichedSecurityEvent.java
      Rule.java
      GeoLocation.java
    enums/
      RuleCategory.java
      Severity.java
      Action.java
    service/
      AttackTypeClassifier.java
      ThreatScoreCalculator.java
    error/
      DomainValidationException.java
      InvalidRequestException.java
  infrastructure/
    clickhouse/
      ClickHouseEventRepository.java
      ClickHouseSchema.sql
    time/
      SystemClockProvider.java
  generator/
    SecurityEventGenerator.java
```

Main rule:

- `domain` should not know about Spring, HTTP, JSON, ClickHouse, JDBC, or Docker.
- `application` orchestrates use cases.
- `api` handles HTTP and DTO conversion.
- `infrastructure` contains IO and external effects.
- `generator` creates test data.

---

## 5. Effects / IO Policy

Keep effects centralized and explicit.

Effects include:

- Database reads/writes.
- Reading the current time.
- HTTP input/output.
- Random generation.
- File generation.
- Console output.

Rules:

1. Business logic should be pure where possible.
2. No database access inside domain services.
3. No `Instant.now()` inside business logic. Use `ClockProvider`.
4. No random generation inside enrichment logic.
5. Controllers should be thin.
6. Services should orchestrate.
7. Repositories should be the only code that talks to the database.
8. Exceptions should be mapped to HTTP responses in one centralized place.
9. Effective logs DEUBG/ERROR/INFO

Example:

```java
public interface ClockProvider {
    Instant now();
}
```

```java
public interface EventReadRepository {
    long countByClientIpBetween(String clientIp, Instant fromInclusive, Instant toExclusive);
}
```

```java
public interface EventWriteRepository {
    void saveAll(List<EnrichedSecurityEvent> events);
}
```

The repeat-offender calculation needs historical data, so the IO should happen in the application layer:

1. Application service asks repository for count from the same IP in the last 10 minutes.
2. Application service passes this count or boolean into the pure threat-score calculator.
3. Pure calculator returns the score.

---

## 6. Error Handling Policy

All error handling must be centralized.

Use one global Spring error handler:

```java
@RestControllerAdvice
public class ErrorHandlingAdvice {
}
```

Do not build ad-hoc error responses inside each controller.

Expected behavior:

- Validation errors return HTTP 400.
- Invalid enum values return HTTP 400.
- Invalid timestamp format returns HTTP 400.
- Invalid `limit` or `offset` returns HTTP 400.
- Unexpected internal errors return HTTP 500 with a safe generic message.
- Do not leak stack traces to clients.

Suggested API error response:

```json
{
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": [
    {
      "field": "rule.category",
      "message": "must be one of INJECTION, XSS, PROTOCOL_VIOLATION, DATA_LEAKAGE, BOT, DOS, RATE_LIMIT"
    }
  ]
}
```

Create a small hierarchy:

```text
ApiException
  InvalidRequestException
  DomainValidationException
  StorageException
```

Map them in one place.

---

## 7. Interfaces / Ports for Testability

Use interfaces for external dependencies and query boundaries.

This makes the system easier to test and easier to replace later.

Required interfaces:

```java
public interface EventWriteRepository {
    void saveAll(List<EnrichedSecurityEvent> events);
}
```

```java
public interface EventReadRepository {
    long countByClientIpBetween(String clientIp, Instant fromInclusive, Instant toExclusive);
}
```

```java
public interface EventQueryRepository {
    SummaryStats getSummary(SummaryQuery query);
    SamplePage getSamples(SamplesQuery query);
    TimeSeriesResponse getTimeSeries(TimeSeriesQuery query);
}
```

```java
public interface ClockProvider {
    Instant now();
}
```

Do not introduce unnecessary interfaces for every class. Use interfaces where they create real value:

- database adapters
- time
- potentially generator output

Keep pure domain services as simple classes.

---

## 8. Domain Model

Incoming event fields:

```text
eventId
timestamp
configId
policyId
clientIp
hostname
path
method
statusCode
userAgent
rule.id
rule.name
rule.message
rule.severity
rule.category
action
geoLocation.country
geoLocation.city
requestSize
responseSize
```

Enriched fields:

```text
attackType
threatScore
receivedAt
```

Enums:

```java
RuleCategory:
  INJECTION
  XSS
  PROTOCOL_VIOLATION
  DATA_LEAKAGE
  BOT
  DOS
  RATE_LIMIT

Severity:
  CRITICAL
  HIGH
  MEDIUM
  LOW

Action:
  DENY
  ALERT
  MONITOR
```

---

## 9. Classification Rules

Map `rule.category` to `attackType`:

| Category | Attack Type |
|---|---|
| INJECTION | SQL/Command Injection |
| XSS | Cross-Site Scripting |
| PROTOCOL_VIOLATION | Protocol Anomaly |
| DATA_LEAKAGE | Data Exfiltration |
| BOT | Bot Activity |
| DOS | Denial of Service |
| RATE_LIMIT | Rate Limiting |

Implement this as pure logic.

Example:

```java
public final class AttackTypeClassifier {
    public String classify(RuleCategory category) {
        return switch (category) {
            case INJECTION -> "SQL/Command Injection";
            case XSS -> "Cross-Site Scripting";
            case PROTOCOL_VIOLATION -> "Protocol Anomaly";
            case DATA_LEAKAGE -> "Data Exfiltration";
            case BOT -> "Bot Activity";
            case DOS -> "Denial of Service";
            case RATE_LIMIT -> "Rate Limiting";
        };
    }
}
```

---

## 10. Threat Score Rules

Threat score is an integer from 0 to 100.

Base score by severity:

```text
CRITICAL = 40
HIGH     = 30
MEDIUM   = 20
LOW      = 10
```

Additional score by action:

```text
DENY    = +20
ALERT   = +10
MONITOR = +0
```

Sensitive path bonus:

```text
If path contains /admin or /login, add +15
```

Repeat offender bonus:

```text
If more than 5 events from the same clientIp exist within the last 10 minutes, add +15
```

Cap:

```text
min(score, 100)
```

Important implementation detail:

The calculator should be pure.

Suggested method:

```java
public int calculate(
    Severity severity,
    Action action,
    String path,
    boolean repeatOffender
)
```

The repository/application layer should decide `repeatOffender`.

---

## 11. Ingestion API

Endpoint:

```http
POST /v1/events/ingest
```

Must accept:

1. A single event object.
2. A batch array of events.

Examples:

```json
{
  "eventId": "evt-001",
  "timestamp": "2026-05-20T14:32:10Z",
  "configId": 14227,
  "policyId": "pol_web1",
  "clientIp": "203.0.113.42",
  "hostname": "www.example.com",
  "path": "/api/v1/login",
  "method": "POST",
  "statusCode": 403,
  "userAgent": "Mozilla/5.0",
  "rule": {
    "id": "950001",
    "name": "SQL_INJECTION",
    "message": "SQL Injection Attack Detected",
    "severity": "CRITICAL",
    "category": "INJECTION"
  },
  "action": "DENY",
  "geoLocation": {
    "country": "CN",
    "city": "Beijing"
  },
  "requestSize": 1024,
  "responseSize": 256
}
```

Batch:

```json
[
  { "...": "..." },
  { "...": "..." }
]
```

Response:

- `201 Created` on success.
- `400 Bad Request` with structured error details on validation failure.

Suggested success response:

```json
{
  "ingested": 2
}
```

Do not return huge enriched event lists from ingestion unless necessary.

---

## 12. Statistics API

Endpoint:

```http
GET /v1/stats/summary?configId={configId}&from={ISO8601}&to={ISO8601}
```

Requirements:

- `configId` is optional.
- `from` and `to` are required unless clearly documented otherwise.
- If `configId` is omitted, aggregate across all configurations.
- Return total events.
- Return count and average threat score by category.
- Return counts by action.
- Return top 10 attackers by event count.
- Return top 10 targeted paths by event count.

Response shape:

```json
{
  "configId": 14227,
  "timeRange": {
    "from": "2026-05-20T00:00:00Z",
    "to": "2026-05-21T00:00:00Z"
  },
  "totalEvents": 1523,
  "byCategory": {
    "INJECTION": {
      "count": 450,
      "avgThreatScore": 72.3
    }
  },
  "byAction": {
    "DENY": 890,
    "ALERT": 433,
    "MONITOR": 200
  },
  "topAttackers": [
    {
      "clientIp": "203.0.113.42",
      "count": 87,
      "avgThreatScore": 81.2
    }
  ],
  "topTargetedPaths": [
    {
      "path": "/api/v1/login",
      "count": 234
    }
  ]
}
```

---

## 13. Samples API

Endpoint:

```http
GET /v1/events/samples?configId={configId}&from={ISO8601}&to={ISO8601}&category={category}&action={action}&limit={limit}&offset={offset}
```

Requirements:

- All filters are optional.
- `limit` default: 20.
- `limit` max: 100.
- `offset` default: 0.
- Sort by `timestamp` descending.
- Return total count for pagination.
- Return enriched events.

Suggested response:

```json
{
  "total": 250,
  "limit": 20,
  "offset": 0,
  "items": [
    {
      "eventId": "evt-001",
      "timestamp": "2026-05-20T14:32:10Z",
      "configId": 14227,
      "clientIp": "203.0.113.42",
      "path": "/api/v1/login",
      "rule": {
        "severity": "CRITICAL",
        "category": "INJECTION"
      },
      "action": "DENY",
      "attackType": "SQL/Command Injection",
      "threatScore": 90,
      "receivedAt": "2026-05-20T14:32:11Z"
    }
  ]
}
```

---

## 14. Data Generator

Create a generator script or Java module.

Preferred simple option:

```text
scripts/generate-events.py
```

or:

```text
src/main/java/.../generator/SecurityEventGenerator.java
```

It should:

- Generate realistic-looking random events.
- Generate a configurable number of events.
- Simulate attack waves.
- Output JSON array that can be sent to `/v1/events/ingest`.

Example usage:

```bash
python scripts/generate_events.py --count 10000 --output generated-events.json
curl -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  --data @generated-events.json
```

Attack wave example:

```text
50 events from the same IP against /api/v1/login within 3 minutes
```

This is required to test:

- top attackers
- top paths
- repeat offender threat score
- category/action distributions

---

## 15. ClickHouse Schema

Suggested table:

```sql
CREATE TABLE IF NOT EXISTS security_events
(
    event_id String,
    timestamp DateTime64(3, 'UTC'),
    received_at DateTime64(3, 'UTC'),

    config_id UInt64,
    policy_id String,
    client_ip String,
    hostname String,
    path String,
    method LowCardinality(String),
    status_code UInt16,
    user_agent String,

    rule_id String,
    rule_name String,
    rule_message String,
    rule_severity LowCardinality(String),
    rule_category LowCardinality(String),

    action LowCardinality(String),

    geo_country LowCardinality(String),
    geo_city String,

    request_size UInt64,
    response_size UInt64,

    attack_type LowCardinality(String),
    threat_score UInt8
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (config_id, timestamp, client_ip, event_id);
```

Notes:

- `timestamp` is event time.
- `received_at` is server ingestion time.
- Order by supports time-range queries per config and attacker queries.
- Use `LowCardinality(String)` for repeated categorical values.
- For this assignment, duplicates may either be rejected by `event_id` at application level or documented as a future improvement.

If implementing duplicate protection, consider checking `event_id` before insert. If time is limited, document that idempotency would be improved in production.

---

## 16. Required Tests

Write small tests as soon as each module is created.

Minimum tests:

### Unit tests

1. `AttackTypeClassifierTest`
   - Each category maps to the correct attack type.

2. `ThreatScoreCalculatorTest`
   - Severity score.
   - Action score.
   - `/login` bonus.
   - `/admin` bonus.
   - Repeat offender bonus.
   - Cap at 100.

3. `EventIngestionServiceTest`
   - Calls classifier/calculator.
   - Uses `ClockProvider`.
   - Saves enriched events.
   - Handles batch ingestion.

### Integration test

At least one Spring API integration test:

- `POST /v1/events/ingest` with valid payload returns 201.
- Invalid enum or missing field returns 400.

If time allows:

- Test samples pagination.
- Test summary aggregation.

---

## 17. Commit Strategy

The PRD explicitly asks for meaningful commits and small logical milestones.

Use small commits so reviewers can see the development process.

Recommended commit sequence:

```text
chore: initialize Spring Boot project
chore: add Docker Compose with ClickHouse
feat(domain): add security event model and enums
feat(api): add ingestion DTOs and validation
feat(domain): add attack type classification
test(domain): cover attack type classification
feat(domain): add threat score calculator
test(domain): cover threat score calculation
feat(app): implement ingestion service
feat(storage): persist enriched events in ClickHouse
test(api): add ingestion integration test
feat(stats): add summary query API
feat(samples): add samples API with filters and pagination
feat(generator): add realistic event data generator
docs: add README with run instructions and architecture
```

Recommended tags:

```bash
git tag v0.1-ingestion
git tag v0.2-enrichment
git tag v0.3-stats
git tag v0.4-generator
git tag v1.0-core-complete
```

Only create tags after the corresponding code is working.

---

## 18. Implementation Order

### Phase 1 — Project skeleton

Goal:

- Application starts.
- Health endpoint or default Spring Boot app works.
- Docker Compose starts ClickHouse.
- README has initial run instructions.

### Phase 2 — Domain and pure logic

Goal:

- Domain records/classes.
- Enums.
- Attack classifier.
- Threat score calculator.
- Unit tests for pure logic.

### Phase 3 — Ingestion

Goal:

- `POST /v1/events/ingest`
- Single event and batch support.
- Validation.
- `receivedAt`.
- Save enriched events.

### Phase 4 — Storage

Goal:

- ClickHouse table.
- Repository adapter.
- Insert enriched events.
- Query repeat offender count.

### Phase 5 — Summary API

Goal:

- `/v1/stats/summary`
- category aggregation
- action aggregation
- top attackers
- top targeted paths

### Phase 6 — Samples API

Goal:

- `/v1/events/samples`
- optional filters
- limit/offset
- max limit 100
- timestamp descending
- total count

### Phase 7 — Generator

Goal:

- Generate random events.
- Generate attack waves.
- Output JSON file.
- README explains how to ingest generated data.

### Phase 8 — README and cleanup

Goal:

- Architecture overview.
- DB choice justification.
- API docs with curl examples.
- Testing instructions.
- Trade-offs.
- What would be improved with more time.
- Known limitations.

### Phase 9 — Optional bonus

Only if the core is fully working:

```http
GET /v1/stats/timeseries?configId={configId}&from={}&to={}&interval={1m|5m|1h}
```

Do not start bonus work before the core is stable.

---

## 19. Optional Bonus Recommendation

Implement only one optional bonus: **Time-Series Dashboard Data**.

Why:

- It fits the analytics nature of the project.
- It is useful for dashboards.
- It reuses the same storage and query layer.
- It is easier and safer than alerting or rate limiting.
- It is easy to explain in the follow-up interview.

Expected behavior:

```http
GET /v1/stats/timeseries?configId=14227&from=2026-05-20T00:00:00Z&to=2026-05-20T01:00:00Z&interval=5m
```

Response:

```json
{
  "configId": 14227,
  "interval": "5m",
  "buckets": [
    {
      "from": "2026-05-20T00:00:00Z",
      "to": "2026-05-20T00:05:00Z",
      "count": 42
    }
  ]
}
```

---

## 20. README Checklist

The README must include:

1. Project overview.
2. Architecture diagram.
3. Technology stack.
4. Storage choice justification.
5. How to build.
6. How to run.
7. How to run tests.
8. How to generate data.
9. API documentation with curl examples.
10. Example responses.
11. Testing strategy.
12. Trade-offs.
13. What would be improved with more time.
14. Known limitations.
15. How AI tools were used, if you want to be transparent.

Suggested architecture diagram:

```text
Client / Generator
      |
      v
Spring REST Controllers
      |
      v
Application Services
      |
      +--> Pure Domain Logic
      |      - AttackTypeClassifier
      |      - ThreatScoreCalculator
      |
      v
Repository Ports
      |
      v
ClickHouse Adapter
      |
      v
ClickHouse
```

---

## 21. What to Prepare for the Interview

Be ready to explain:

1. Why ClickHouse was chosen.
2. Why business logic is pure and isolated.
3. How validation works.
4. How centralized error handling works.
5. How threat score is calculated.
6. How repeat offender detection works.
7. Performance implications of checking repeat offender per ingested event.
8. How the APIs query aggregations.
9. How you would scale ingestion.
10. How you would add Kafka later if needed.
11. What indexes/order keys are used in ClickHouse.
12. What tests exist and why.
13. What you would improve with more time.

Repeat offender discussion:

Current simple approach:

- For each event, query count of events from same IP in `[event.timestamp - 10 minutes, event.timestamp)`.
- If count > 5, add +15.

Production improvements:

- Maintain a rolling counter in Redis.
- Use stream processing with Kafka/Flink.
- Use materialized views in ClickHouse.
- Batch repeat-offender computation for bulk ingestion.
- Use approximate counters/windowed aggregations for very high scale.

---

## 22. Non-Goals

Do not implement these unless explicitly asked later:

- Kafka ingestion.
- Kubernetes.
- Full authentication.
- Full UI/dashboard.
- Complex distributed processing.
- Perfect idempotency.
- Full production observability stack.
- Too many abstractions.

---

## 23. Definition of Done

The project is ready to submit when:

- `./mvnw test` passes.
- The app starts locally.
- ClickHouse starts with Docker Compose.
- `POST /v1/events/ingest` works with single event and batch.
- Enriched fields are stored.
- `GET /v1/stats/summary` works.
- `GET /v1/events/samples` works.
- Data generator creates realistic events and attack waves.
- README explains build/run/API/design/trade-offs.
- Commits are small and meaningful.
- Tags exist for milestones.
- No code exists that cannot be explained in the interview.
