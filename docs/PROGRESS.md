# Implementation Progress

Last updated: 2026-10-13

| Milestone | Status | Evidence / next acceptance checks |
| --- | --- | --- |
| 1. Architecture and project foundation | Complete | Java 21 Maven reactor and health endpoint committed; tested with Java 25 using `--release 21`. |
| 2. PostgreSQL persistence and job management REST API | Complete | `mvn -B -ntp clean verify`: 23 tests passed, 0 failed/errors; includes PostgreSQL 16 Testcontainers migration and REST integration tests. Compose config validated. |
| 3. Idempotent submission and outbox | Complete | Optional operation-scoped idempotency with 24-hour retention; transactional `JobCreated` outbox; PostgreSQL integration coverage. No publisher/Kafka delivery is implemented yet. |
| 4. Scheduling and Kafka dispatch | Complete | PostgreSQL-backed `SKIP LOCKED` outbox claims, Kafka acknowledgement sequencing, scheduled eligibility, bounded retries/leases, and PostgreSQL+Kafka Testcontainers coverage. |
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

## Delivered in milestone 3

- Flyway V2 adds operation/key-scoped idempotency records and constrained
  outbox events with JSONB payload, retry state, and publisher claim leases.
- Optional `Idempotency-Key` on job creation. Canonical request fingerprints
  distinguish equivalent retries from conflicting reuse (`409 Conflict`).
  PostgreSQL transaction-scoped advisory locks serialize concurrent requests
  for the same operation/key across application instances; the primary key is
  the final uniqueness guard.
- Job, idempotency record, and `JobCreated` outbox event are stored in the same
  PostgreSQL transaction. A database-backed claim design supports future
  concurrent publishers using `FOR UPDATE SKIP LOCKED`.
- Idempotency keys are retained for 24 hours; expired keys may be reused, and
  an hourly scheduled cleanup removes expired records. Keys are scoped to
  `POST:/api/v1/jobs`, not to a tenant. Add tenant scoping before enabling
  multi-tenant use.
- No Kafka producer or publisher is implemented. Future outbox delivery is
  at least once, so consumers and external side effects must be idempotent;
  exactly-once distributed execution is not promised.

## Delivered in milestone 4

- Apache Kafka 3.9.1 KRaft broker in local Compose, with three default
  partitions and configurable host port/bootstrap address.
- Versioned `JobCreated` v1 JSON envelope and schema in
  `libs/contracts/events/`; Kafka topic is `flowforge.job-dispatch.v1`, keyed
  by job UUID to keep each job's events on one partition.
- Bounded outbox polling and batch claiming with PostgreSQL
  `FOR UPDATE SKIP LOCKED`. Claims use leases and increment attempt counts;
  expired final claims are terminalized so publisher crashes cannot cause
  unbounded retries.
- Kafka producer waits for broker acknowledgement outside database
  transactions, then atomically marks the event published and moves the job
  from `DISPATCHING` to `DISPATCHED`. `DISPATCHED` is not worker execution or
  successful completion.
- Future scheduled jobs become claimable only when their scheduled time is
  due. Cancelling a job before acknowledgement marks its unpublished outbox
  event as cancelled. A cancellation racing an already-started broker send
  cannot retract a broker-accepted message; future consumers must check the
  current PostgreSQL job state before starting work.
- Kafka errors use bounded exponential backoff and a terminal publication
  failure state. Micrometer tracks pending events, successful publication,
  failures, terminal failures, and dispatch latency.
- PostgreSQL and Kafka Testcontainers coverage verifies acknowledgements,
  outage/recovery behavior, duplicate delivery after a simulated crash,
  concurrent publishers, lease expiry, due scheduling, cancellation, and
  terminal retry exhaustion.

## Explicitly not delivered

Kafka consumers/workers and execution retries, workflow definitions,
multi-tenant authentication, Redis, Prometheus/Grafana dashboards, and the
web console remain future milestones. Job retry settings are persisted but
are not yet acted on by a worker.
