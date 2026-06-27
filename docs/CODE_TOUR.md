# Code Tour — learning Mini WSA end-to-end

A guided reading order for the codebase, plus the high-level design and two end-to-end
walkthroughs. Read this alongside the [README](../README.md) (which covers *what* the service does
and how to run it); this doc covers *how the code is shaped and why*.

---

## 1. High-level design in one minute

Mini WSA is a **security-event analytics pipeline**: accept events over REST → **enrich** each
(attack type, a 0–100 threat score, a server `receivedAt`) → **store** in ClickHouse → expose
**analytics** (summary, samples, time-series).

It is built in **clean / hexagonal** layers with a strict **dependency rule**: dependencies point
*inward*, and the center is **pure** (no Spring, HTTP, JSON, or JDBC).

```
        api  ─────►  application  ─────►  domain   ◄─── the pure core
   (controllers,     (use cases +        (model + rules)
    DTOs, errors)     ports)
        ▲                  ▲
        │                  │ implements ports
        └──── infrastructure (adapters: ClickHouse, in-memory, clock, metrics)
```

- **domain** depends on *nothing* (plain Java). It holds the data model and the scoring/classification rules.
- **application** depends only on the domain. It orchestrates use cases and declares **ports**
  (interfaces) for the effects it needs (storage, clock).
- **api** and **infrastructure** depend inward on application/domain. The api is the HTTP boundary;
  infrastructure provides the concrete adapters that implement the ports.

The payoff: the business logic is testable without a web server or a database, and storage is a
runtime choice (`miniwsa.storage=memory|clickhouse`) behind the same ports.

### Package map

| Package | Role | Depends on |
|---|---|---|
| `domain.model` / `domain.enums` | Immutable records + enums: the event, rule, enriched event | nothing |
| `domain.service` | Pure rules: `AttackTypeClassifier`, `ThreatScoreCalculator` | domain only |
| `application` | Use-case services: ingestion, stats, samples | domain |
| `application.ports` | Interfaces for effects: write/read/query repos, clock | domain |
| `application.query` | Query/result value objects (`SummaryQuery`, `SummaryStats`, …) | domain |
| `api` + `api.dto` + `api.validation` | Controllers, request/response DTOs, validation, one error handler | application |
| `infrastructure.*` | Adapters: ClickHouse + in-memory repos, system clock | application (implements ports) |
| `config` | Spring wiring: domain beans, Jackson, limits, OpenAPI | — |
| `observability` | `IngestionMetrics` (Micrometer) | domain |
| `generator` | Standalone test-data generator | domain |

---

## 2. Reading order

Read **inside-out** — start at the pure core, then the use cases, then the boundaries. Each step
says what to notice.

### Session 1 — the domain (the pure core)
1. [`domain/model/SecurityEvent.java`](../src/main/java/com/akamai/miniwsa/domain/model/SecurityEvent.java) — the raw event. Note it's a plain `record`: no annotations, no framework.
2. [`domain/model/Rule.java`](../src/main/java/com/akamai/miniwsa/domain/model/Rule.java), [`GeoLocation.java`](../src/main/java/com/akamai/miniwsa/domain/model/GeoLocation.java), [`domain/enums/*`](../src/main/java/com/akamai/miniwsa/domain/enums) — the supporting types (`Severity`, `Action`, `RuleCategory`).
3. [`domain/model/EnrichedSecurityEvent.java`](../src/main/java/com/akamai/miniwsa/domain/model/EnrichedSecurityEvent.java) — the event *after* enrichment (adds `attackType`, `threatScore`, `receivedAt`).
4. [`domain/service/AttackTypeClassifier.java`](../src/main/java/com/akamai/miniwsa/domain/service/AttackTypeClassifier.java) — category → human-readable string. An exhaustive `switch` (a new category won't compile until classified).
5. [`domain/service/ThreatScoreCalculator.java`](../src/main/java/com/akamai/miniwsa/domain/service/ThreatScoreCalculator.java) — **the scoring math**. Notice `repeatOffender` is an *input boolean*, not computed here — deciding it needs storage, which is an effect the domain refuses to own. This is the key purity decision.

### Session 2 — the application (use cases + ports)
6. [`application/ports/EventWriteRepository.java`](../src/main/java/com/akamai/miniwsa/application/ports/EventWriteRepository.java), [`EventReadRepository.java`](../src/main/java/com/akamai/miniwsa/application/ports/EventReadRepository.java), [`EventQueryRepository.java`](../src/main/java/com/akamai/miniwsa/application/ports/EventQueryRepository.java), [`ClockProvider.java`](../src/main/java/com/akamai/miniwsa/application/ports/ClockProvider.java) — the **ports**. The application says what it needs; infrastructure provides it.
7. [`application/EventIngestionService.java`](../src/main/java/com/akamai/miniwsa/application/EventIngestionService.java) — **the heart of ingestion.** Read the `ingest` method top to bottom: stamp `receivedAt`, load repeat-offender history (**one** query per batch), enrich each event, persist, record metrics. Then read the `isRepeatOffender` Javadoc — it documents a deliberate reading of an ambiguous spec.
8. [`application/query/*`](../src/main/java/com/akamai/miniwsa/application/query) — the query inputs/outputs (`SummaryQuery`/`SummaryStats`, `SamplesQuery`/`SamplePage`, `TimeSeriesQuery`/`TimeSeriesBucket`, `Interval`).
9. [`application/StatsService.java`](../src/main/java/com/akamai/miniwsa/application/StatsService.java) + [`SamplesService.java`](../src/main/java/com/akamai/miniwsa/application/SamplesService.java) — thin orchestration over `EventQueryRepository`. `SamplesService` also applies the pagination defaults/clamp.

### Session 3 — the api (the HTTP boundary)
10. [`api/dto/IngestEventRequest.java`](../src/main/java/com/akamai/miniwsa/api/dto/IngestEventRequest.java) + [`RuleDto.java`](../src/main/java/com/akamai/miniwsa/api/dto/RuleDto.java) — request DTOs. **All JSON/validation lives here** so the domain stays clean. Note `toDomain()` maps DTO → domain.
11. [`api/EventIngestionController.java`](../src/main/java/com/akamai/miniwsa/api/EventIngestionController.java) — thin: validate, map, delegate. Never throws.
12. [`api/validation/BatchSizeWithinLimit.java`](../src/main/java/com/akamai/miniwsa/api/validation/BatchSizeWithinLimit.java) + [`BatchSizeValidator.java`](../src/main/java/com/akamai/miniwsa/api/validation/BatchSizeValidator.java) — a **custom Bean Validation constraint** whose validator is a Spring bean reading config — that's how the batch cap stays both configurable *and* framework-validated (no `throw` in the controller).
13. [`api/dto/SummaryParams.java`](../src/main/java/com/akamai/miniwsa/api/dto/SummaryParams.java) (and `SamplesParams`, `TimeSeriesParams`) — query-param binding + constraints, including the `@AssertTrue` range check.
14. [`api/StatsController.java`](../src/main/java/com/akamai/miniwsa/api/StatsController.java) + [`SamplesController.java`](../src/main/java/com/akamai/miniwsa/api/SamplesController.java) + the response DTOs (`SummaryResponse`, `SamplesResponse`, `SampleItem`, `TimeSeriesResponse`).
15. [`api/ErrorHandlingAdvice.java`](../src/main/java/com/akamai/miniwsa/api/ErrorHandlingAdvice.java) — **the single place** exceptions become RFC 7807 `ProblemDetail` responses. Controllers stay thin because everything funnels here.

### Session 4 — infrastructure + wiring
16. [`infrastructure/memory/InMemoryEventRepository.java`](../src/main/java/com/akamai/miniwsa/infrastructure/memory/InMemoryEventRepository.java) — implements all three repo ports with plain streams. The **default** adapter; read this first because it's the easiest way to understand what the queries *mean*.
17. [`infrastructure/clickhouse/ClickHouseEventRepository.java`](../src/main/java/com/akamai/miniwsa/infrastructure/clickhouse/ClickHouseEventRepository.java) — the same ports in SQL. Compare its `getSummary` to the in-memory one. Active only when `miniwsa.storage=clickhouse`.
18. [`infrastructure/clickhouse/ClickHouseConfig.java`](../src/main/java/com/akamai/miniwsa/infrastructure/clickhouse/ClickHouseConfig.java) + [`infrastructure/time/SystemClockProvider.java`](../src/main/java/com/akamai/miniwsa/infrastructure/time/SystemClockProvider.java) — the `DataSource`/`JdbcTemplate` and the real clock.
19. [`config/DomainConfig.java`](../src/main/java/com/akamai/miniwsa/config/DomainConfig.java) — exposes the pure domain services as beans (so the domain stays Spring-free). Then [`LimitsProperties.java`](../src/main/java/com/akamai/miniwsa/config/LimitsProperties.java), [`JacksonConfig.java`](../src/main/java/com/akamai/miniwsa/config/JacksonConfig.java), [`OpenApiConfig.java`](../src/main/java/com/akamai/miniwsa/config/OpenApiConfig.java).
20. [`observability/IngestionMetrics.java`](../src/main/java/com/akamai/miniwsa/observability/IngestionMetrics.java) — the Micrometer meters, recorded by the ingestion service.
21. [`MiniWsaApplication.java`](../src/main/java/com/akamai/miniwsa/MiniWsaApplication.java) — the entry point; note `DataSourceAutoConfiguration` is excluded so the in-memory profile boots with no DB.

### Session 5 — generator + tests
22. [`generator/SecurityEventGenerator.java`](../src/main/java/com/akamai/miniwsa/generator/SecurityEventGenerator.java) + [`GeneratorMain.java`](../src/main/java/com/akamai/miniwsa/generator/GeneratorMain.java) — seeded test-data generation, including "attack waves".
23. Tests, in this order: [`ThreatScoreCalculatorTest`](../src/test/java/com/akamai/miniwsa/domain/service/ThreatScoreCalculatorTest.java) → [`EventIngestionServiceTest`](../src/test/java/com/akamai/miniwsa/application/EventIngestionServiceTest.java) (fakes for clock/repos) → [`EventIngestionControllerTest`](../src/test/java/com/akamai/miniwsa/api/EventIngestionControllerTest.java) (full `@SpringBootTest`) → [`ClickHouseEventRepositoryTest`](../src/test/java/com/akamai/miniwsa/infrastructure/clickhouse/ClickHouseEventRepositoryTest.java) (Testcontainers) → [`FullPipelineE2ETest`](../src/test/java/com/akamai/miniwsa/e2e/FullPipelineE2ETest.java).

---

## 3. End-to-end walkthrough A — ingesting an event

`POST /v1/events/ingest` with one event:

1. **Bind + validate** — Spring deserializes the body into `List<IngestEventRequest>` (a single
   object also binds, via Jackson's `ACCEPT_SINGLE_VALUE_AS_ARRAY` in `JacksonConfig`). Bean
   Validation runs: `@NotEmpty`, the custom `@BatchSizeWithinLimit` (its validator reads
   `LimitsProperties.maxBatchSize`), and `@Valid` on each element + its `RuleDto`. Any failure →
   `ErrorHandlingAdvice` → `400` ProblemDetail. **The controller never sees invalid input.**
2. **Map to domain** — `EventIngestionController` calls `IngestEventRequest.toDomain()` → a pure
   `SecurityEvent`. JSON concerns stay in the DTO.
3. **Orchestrate** — `EventIngestionService.ingest(List<SecurityEvent>)`:
   - `clock.now()` (via `ClockProvider` → `SystemClockProvider`) → `receivedAt`.
   - `loadRepeatOffenderHistory(...)` issues **one** `EventReadRepository.findEventTimestampsByClientIp`
     query covering every IP in the batch over the union of their 10-min windows.
   - For each event: `isRepeatOffender(...)` counts prior timestamps **in memory**, then `enrich(...)`
     calls `AttackTypeClassifier.classify(...)` and `ThreatScoreCalculator.calculate(...)` →
     `EnrichedSecurityEvent`.
   - `writeRepository.saveAll(enriched)` → the active adapter (ClickHouse or in-memory).
   - `metrics.recordBatch(enriched, repeatOffenders)` → Micrometer counters/summaries.
4. **Respond** — returns the count; the controller wraps it as `IngestResponse` → `201 {"ingested":N}`.

The only effects (clock, the repo query, the insert, metrics) are at the edges; the scoring in the
middle is pure and unit-testable with fakes — see `EventIngestionServiceTest`.

## 4. End-to-end walkthrough B — a summary query

`GET /v1/stats/summary?configId=…&from=…&to=…`:

1. **Bind + validate** — `StatsController.summary(@Valid @ParameterObject SummaryParams)`. The
   params carry the constraints (`from`/`to` required, `to` after `from` via `@AssertTrue`). Invalid
   → `400` via the advice.
2. **To a query object** — the controller builds a `SummaryQuery(configId, from, to)` and calls
   `StatsService.summary(query)`.
3. **Delegate to the port** — `StatsService` calls `EventQueryRepository.getSummary(query)`. The
   active adapter does the heavy lifting:
   - `ClickHouseEventRepository.getSummary` runs grouped `count()/avg()` SQL and `ORDER BY … LIMIT
     summaryTopLimit` for the top lists (limit from `LimitsProperties`).
   - `InMemoryEventRepository.getSummary` does the same with Java streams (`groupingBy`, sorted,
     `.limit(summaryTopLimit())`).
4. **Map to the response** — `SummaryResponse.from(...)` shapes `SummaryStats` into the JSON DTO
   (echoing `configId`/range; `@JsonInclude(NON_NULL)` drops `configId` when querying across all).

Samples and time-series follow the identical shape — controller → `SamplesQuery`/`TimeSeriesQuery`
→ service → `EventQueryRepository` → adapter → response DTO.

---

## 5. Where the key decisions live

| Decision | File(s) |
|---|---|
| Threat-score formula + the 0–100 cap | `domain/service/ThreatScoreCalculator.java` |
| Repeat-offender semantics (which events count, why) | `application/EventIngestionService.java` (`isRepeatOffender` Javadoc) |
| N+1 avoidance (one history query per batch) | `application/EventIngestionService.java` (`loadRepeatOffenderHistory`) |
| Pure/effectful split (clock & history as inputs) | `ThreatScoreCalculator` + `application.ports.*` |
| One place for all error responses | `api/ErrorHandlingAdvice.java` |
| Configurable batch cap without a `throw` | `api/validation/BatchSizeWithinLimit.java` + `BatchSizeValidator.java` |
| Storage chosen at runtime | `@ConditionalOnProperty` on the two `infrastructure.*` repos |
| ClickHouse table design (MergeTree, ORDER BY) | `src/main/resources/db/ClickHouseSchema.sql` |
| Externalized limits + how to change them live | `config/LimitsProperties.java` + README "Configuration" |
| Domain stays Spring-free | `config/DomainConfig.java` |

---

## 6. Experiment with it

The fastest feedback loop is the in-memory profile (no DB):

```bash
./mvnw spring-boot:run
# ingest, then read it back:
curl -X POST localhost:8080/v1/events/ingest -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","timestamp":"2026-05-20T10:00:00Z","configId":1,"clientIp":"9.9.9.9",
       "path":"/admin","method":"GET","statusCode":200,
       "rule":{"id":"1","severity":"HIGH","category":"BOT"},"action":"DENY"}'
curl "localhost:8080/v1/stats/summary?configId=1&from=2026-05-20T00:00:00Z&to=2026-05-21T00:00:00Z"
```

Good experiments to cement understanding:
- Change a weight in `ThreatScoreCalculator`, re-run `ThreatScoreCalculatorTest`, watch it fail.
- Set a breakpoint in `EventIngestionService.ingest` and step through enrichment.
- Flip `miniwsa.storage=clickhouse` (with `docker compose up -d clickhouse`) and confirm the same
  API behaves identically — that's the ports paying off.
- Open Swagger UI (`/swagger-ui.html`) and watch metrics move at `/actuator/prometheus`.
