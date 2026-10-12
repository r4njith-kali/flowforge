# Implementation Progress

Last updated: 2026-10-13

| Milestone | Status | Evidence / next acceptance checks |
| --- | --- | --- |
| 1. Architecture and project foundation | Complete | Java 21 Maven reactor and health endpoint committed; tested with Java 25 using `--release 21`. |
| 2. PostgreSQL persistence and job management REST API | Complete | `mvn -B -ntp clean verify`: 23 tests passed, 0 failed/errors; includes PostgreSQL 16 Testcontainers migration and REST integration tests. Compose config validated. |
| 3. Idempotent submission and outbox | Not started | Add tenant-scoped request idempotency and transactional event outbox; verify duplicate requests and restart-safe publication. |
| 4. Scheduling and Kafka dispatch | Not started | Add due-job claiming, dispatch, backpressure, retry scheduling, and dead-letter handling. |
| 5. Worker runtime and recovery | Not started | Add worker handlers, attempt leases, heartbeat, cancellation, and crash recovery. |
| 6. Operator UI | Not started | Add React/TypeScript console and typed API client with accessibility and UI tests. |
| 7. Production readiness | Not started | Add authentication, quotas, retention, Kafka/Redis/monitoring deployment, resilience tests, and runbooks. |

## Delivered in milestone 2

- PostgreSQL 16 local Compose service; database credentials are read from
  ignored `.env` values and required Compose substitutions.
- Flyway V1 migration creates `jobs` with JSONB payload, constrained lifecycle
  status and retry fields, optimistic-lock version, timestamps, and query
  indexes. Hibernate validates the mapping; it does not generate the schema.
- `POST /api/v1/jobs`, filtered/paginated/sorted `GET /api/v1/jobs`,
  `GET /api/v1/jobs/{id}`, and `POST /api/v1/jobs/{id}/cancel`.
- DTO-based request/response handling, validation, Problem Details, bounded
  safe sorting, optimistic state updates, and springdoc OpenAPI.
- JUnit 5 unit/MVC tests and PostgreSQL Testcontainers integration coverage.

## Explicitly not delivered

Kafka publication/consumers, worker execution, retries at runtime, request
idempotency, workflow definitions, multi-tenant authentication, Redis,
Prometheus/Grafana, and the web console remain future milestones. Job retry
settings are persisted but are not yet acted on by a worker.
