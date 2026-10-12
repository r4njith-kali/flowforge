# Implementation Progress

Last updated: 2026-10-13

| Milestone | Status | Evidence / next acceptance checks |
| --- | --- | --- |
| 1. Architecture and project foundation | Complete | Java 21 Maven reactor and health endpoint committed; tested with Java 25 using `--release 21`. |
| 2. PostgreSQL persistence and job management REST API | Complete | `mvn -B -ntp clean verify`: 23 tests passed, 0 failed/errors; includes PostgreSQL 16 Testcontainers migration and REST integration tests. Compose config validated. |
| 3. Idempotent submission and outbox | Complete | Optional operation-scoped idempotency with 24-hour retention; transactional `JobCreated` outbox; PostgreSQL integration coverage. Kafka publication was added in milestone 4. |
| 4. Scheduling and Kafka dispatch | Complete | PostgreSQL-backed `SKIP LOCKED` outbox claims, Kafka acknowledgement sequencing, scheduled eligibility, bounded retries/leases, and PostgreSQL+Kafka Testcontainers coverage. |
| 5. Worker runtime and recovery | Complete | Independent Spring Boot Maven service; Kafka dispatch consumer, PostgreSQL-fenced attempts, leases/heartbeats, cooperative cancellation, bounded retries, and lease-expiry recovery. Verified with PostgreSQL and Kafka Testcontainers. |
| 6. Operator UI | Complete | React/TypeScript Vite dashboard with server-side job management, live detail polling, accessible cancellation/submission, 11 passing UI/API tests, production build, and a live `demo.echo` verification. |
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
  concurrent publishers using `FOR UPDATE SKIP LOCKED`; milestone 4 added the
  Kafka publisher.
- Idempotency keys are retained for 24 hours; expired keys may be reused, and
  an hourly scheduled cleanup removes expired records. Keys are scoped to
  `POST:/api/v1/jobs`, not to a tenant. Add tenant scoping before enabling
  multi-tenant use.
- Outbox delivery is at least once, so consumers and external side effects
  must be idempotent; exactly-once distributed execution is not promised.

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

## Delivered in milestone 5

- Added an independent `services/worker` Spring Boot Maven module and Docker
  Compose service. Workers join one Kafka consumer group and can be scaled
  independently. Compose provides a container-only Kafka listener in addition
  to the existing host listener.
- Flyway V4 additively creates worker lease/fencing/result/error fields and
  persistent `job_attempts` with unique job/attempt numbers and lease indexes.
  The control plane remains the migration owner; start it against the target
  database before deploying workers.
- A Kafka `JobCreated` event triggers a PostgreSQL row-locked claim. The claim
  increments the job attempt and fencing token and persists the worker ID and
  lease before execution begins. Duplicate events cannot claim an active or
  terminal job. Kafka offsets are committed after listener completion; if a
  claimed worker crashes, the independently running PostgreSQL recovery scan
  redrives eligible jobs even after the original offset is committed.
- Registered `JobHandler` implementations include `demo.echo` and bounded
  `demo.delay` (maximum 30 seconds). Unsupported types fail permanently.
  Unexpected errors are transient; handlers may explicitly classify failures.
- Heartbeats renew owned leases. Completion and failure updates require both
  worker ID and current fencing token. Expired attempts are marked
  `ABANDONED`; recovery starts a higher-token attempt or fails the job after
  the configured maximum.
- Transient failures return jobs to `DISPATCHED` with a durable
  `next_attempt_at`; the attempt is `RETRY_WAIT`. Exponential backoff is capped
  by configuration. Per-job and worker maximum-attempt settings both apply.
- Cancelling a running job records `cancel_requested_at` and returns
  `202 Accepted` while the job remains `RUNNING`. Cooperative handlers observe
  the request; a completion racing with cancellation persists `CANCELLED`
  instead of `COMPLETED`. Non-cooperative handler code may continue until it
  returns or its lease expires.
- Worker logs and Micrometer metrics cover execution outcomes, retries,
  fencing, terminal failures, and execution duration. Integration tests cover
  successful execution, unsupported types, duplicate Kafka delivery,
  concurrent claims, retries/exhaustion, expired lease recovery/fencing,
  scheduled eligibility, and cooperative cancellation.
- Delivery and handler side effects remain at least once. Fencing protects
  database state only; downstream side effects must be idempotent. Worker
  schema migrations must be applied by the control-plane Flyway owner first.
- Final verification: `mvn -B -ntp clean verify
  -Dtestcontainers.docker-api-version=1.40` passed all 50 tests (41
  control-plane, 9 worker) using OpenJDK 25.0.1 with Java 21 release
  compilation. Compose configuration validation and the worker Docker image
  build also succeeded. The Testcontainers Docker API override is local-only
  and is not required in CI.
- End-to-end verification (2026-10-13): local PostgreSQL and Kafka became
  healthy, Flyway V1-V4 succeeded, two worker containers ran independently,
  and jobs were submitted through the REST API. `demo.echo` completed with
  one persisted attempt and result; its identical Idempotency-Key replay
  returned the same job ID. A scheduled `demo.delay` remained unclaimed before
  its due time and completed after it. A future scheduled job was cancelled
  with no execution attempts. Both workers were restarted and a subsequent
  echo job completed. See `docs/E2E_DEMO.md` for IDs, database/API evidence,
  logs, reproduction steps, and startup defects found and fixed.

## Delivered in milestone 6

- Added a strict TypeScript React dashboard under `apps/web/`, built with
  Vite 8, Tailwind CSS 4, React Router, and Oxlint. Vite proxies `/api`,
  `/actuator`, Swagger UI, and OpenAPI routes to the environment-configured
  control-plane origin; `.env.example` contains no credentials.
- Added overview counts derived from paginated API totals and the live
  `/actuator/health` endpoint; recent jobs come from the list API.
- Added a jobs table with API-side pagination, exact job-type/status filters,
  and allowlisted sort fields; submission validates JSON object payloads and
  supports schedule, max attempts, retry delay, and optional
  `Idempotency-Key`.
- Added polled job detail with actual DTO metadata and timestamp-based
  timeline, configurable 5/15/30-second refresh, confirmation-based
  cancellation, and explicit 200 / 202 / 409 handling. Results and attempt
  history are marked unavailable because the current REST response does not
  expose them. No execution events are fabricated.
- Added responsive dark UI, semantic navigation/forms, visible focus styling,
  keyboard-operable native controls, loading/empty/error states, and cleanup
  of polling timers.
- Frontend verification: `npm run lint`, `npm run typecheck`, `npm test`
  (11 tests passed), and `npm run build` succeeded in `apps/web/`.
- Live UI check (2026-10-13): submitted `demo.echo` from the dashboard using
  the local control-plane and worker fleet. Job
  `f777c2dc-5748-43f5-858e-204f5faf6346` was verified through the REST API as
  `COMPLETED`, with `attemptCount: 1`, and persisted `startedAt` and
  `completedAt`. Genuine live screenshots are in `docs/images/`.

## Explicitly not delivered

Workflow definitions, multi-tenant authentication, Redis, provisioned
Prometheus/Grafana dashboards, execution-result and attempt-history API
endpoints remain future milestones.
