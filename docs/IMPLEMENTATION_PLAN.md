# FlowForge Architecture and Implementation Plan

## 1. Goals and scope

FlowForge will orchestrate durable, asynchronous workflows with explicit job
state, bounded retries, safe worker recovery, and operational visibility. The
first increment establishes the repository structure and a Java 21 Spring
Boot control-plane service with automated health-endpoint coverage. It does
not yet implement job submission, workflow execution, Kafka consumers, or
infrastructure deployment.

### Initial technology choices

| Concern | Technology | Responsibility |
| --- | --- | --- |
| Backend | Java 21, Spring Boot | Typed service APIs and runtime |
| Work distribution | Apache Kafka | Durable, partitioned asynchronous events |
| Durable state | PostgreSQL | Jobs, attempts, idempotency, outbox, audit |
| Ephemeral coordination | Redis | Short-lived cache/coordination only |
| Operator UI | React, TypeScript | Workflow/job operation and visibility |
| Packaging | Docker | Repeatable service and local dependency runtime |
| Metrics and dashboards | Prometheus, Grafana | Scraping, alerting inputs, and visualization |
| Build | Maven | Root reactor and service modules |

## 2. Target architecture

```text
 React + TypeScript console
             |
             | HTTPS / JSON API
             v
 Control plane (Spring Boot) ---- PostgreSQL
       |                              ^  |
       | transactional outbox          |  | job state + outbox
       v                              |  v
 Scheduler / dispatcher ---------> Kafka topics
                                        |
                                        v
                                  Worker fleet
                                        |
                    claim / heartbeat / result / retry
                                        |
                                        +------> Control plane

 Redis: optional cache and short-lived coordination; never authoritative
 Prometheus: scrapes service metrics; Grafana: dashboards and operations
 Docker: packages services and runs the local dependency stack
```

### Design principles

1. PostgreSQL is the authoritative store for job state and attempt history.
   Kafka is the durable transport, not the only copy of job state.
2. Publish database changes and Kafka events through a transactional outbox;
   consumers are idempotent because delivery is at least once.
3. Keep state transitions explicit and guarded by version/lease checks.
4. A worker may execute more than once after a crash or lease expiry.
   Exactly-once external side effects are not promised; task handlers must
   support idempotency or use downstream idempotency keys.
5. Redis loss must not lose accepted jobs or prevent recovery from PostgreSQL.
6. Isolate tenant identity and authorization at every API and data boundary
   before exposing the service to untrusted users.

## 3. Monorepo structure

```text
apps/
  web/                              React + TypeScript operator console
services/
  control-plane/                    HTTP API, validation, job state commands
  scheduler/                         due-work scan and Kafka dispatch
  worker/                            Kafka consumer and execution lifecycle
libs/
  contracts/
    openapi/                         versioned REST contract
    events/                          versioned Kafka event schemas
  java-common/                       shared IDs, errors, observability helpers
infra/
  docker/                            Dockerfiles and local Compose stack
  monitoring/
    prometheus/                      scrape and alert rules
    grafana/                         provisioned dashboards
  environments/                      deployment-specific configuration
docs/
```

The initial Maven reactor contains the root parent and the
`services/control-plane` module. Modules are added when their corresponding
milestones begin; shared code should only be extracted after a concrete
cross-service reuse case appears.

## 4. Service boundaries

### Control plane

Owns workflow/job API validation, authorization, job creation and cancellation
commands, durable job-state transitions, idempotency records, and outbox
insertion. It does not execute user task code or synchronously wait for a
worker.

### Scheduler and dispatcher

Finds eligible scheduled/retryable jobs, applies concurrency and rate limits,
and publishes dispatch events from the outbox to Kafka. It can be horizontally
scaled using database row claiming (`FOR UPDATE SKIP LOCKED`) and idempotent
publication.

### Worker runtime

Consumes dispatch messages, claims a specific attempt under a time-bounded
lease, executes a registered handler in an isolated execution context, renews
its lease while healthy, and reports completion, failure, or cancellation.
Worker capacity is controlled independently from API capacity.

### Web console

Provides operator workflows, job inspection, attempt/event history, and
operational views. It calls the versioned control-plane API and does not
connect directly to PostgreSQL, Kafka, or Redis.

### Platform observability

Each service exports health and metrics endpoints. Prometheus scrapes the
services; Grafana dashboards visualize request latency, queue lag, dispatch
age, job outcomes, retry counts, lease expiry, and worker utilization. Avoid
job IDs, tenant IDs, and other high-cardinality values as metric labels.

## 5. Data model

Use PostgreSQL migrations (planned: Flyway) and UTC timestamps. Every tenant
owned record is scoped by `tenant_id`. UUID identifiers are generated by the
application and exposed as opaque IDs.

### `workflows`

- `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL`
- `name TEXT NOT NULL`, `definition JSONB NOT NULL`
- `version INTEGER NOT NULL`, `created_at`, `updated_at`
- Unique `(tenant_id, name, version)`; definition is immutable per version.

### `jobs`

- `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL`
- `workflow_id UUID`, `workflow_version INTEGER`
- `status TEXT NOT NULL`, `input JSONB`, `result JSONB`, `error JSONB`
- `priority INTEGER NOT NULL DEFAULT 0`, `run_at TIMESTAMPTZ NOT NULL`
- `attempt_count INTEGER NOT NULL DEFAULT 0`, `max_attempts INTEGER NOT NULL`
- `idempotency_key TEXT`, `created_at`, `updated_at`, `started_at`,
  `finished_at`, `cancel_requested_at`
- `version BIGINT NOT NULL DEFAULT 0` for compare-and-set transitions.
- Index `(tenant_id, status, run_at, priority)` for due work and
  `(tenant_id, created_at DESC)` for job listing.

### `job_attempts`

- `id UUID PRIMARY KEY`, `job_id UUID NOT NULL`, `attempt_number INTEGER`
- `status TEXT NOT NULL`, `worker_id TEXT`, `lease_expires_at TIMESTAMPTZ`
- `started_at`, `heartbeat_at`, `finished_at`
- `error JSONB`, `result JSONB`, `created_at`
- Unique `(job_id, attempt_number)`; index on `(status, lease_expires_at)`.

### `idempotency_keys`

- `tenant_id UUID NOT NULL`, `scope TEXT NOT NULL`, `key TEXT NOT NULL`
- `request_hash TEXT NOT NULL`, `job_id UUID NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`, `expires_at TIMESTAMPTZ`
- Primary key `(tenant_id, scope, key)`. Reusing a key with a different
  request hash returns a conflict rather than silently aliasing a request.

### `outbox_events`

- `id UUID PRIMARY KEY`, `aggregate_type TEXT`, `aggregate_id UUID`
- `event_type TEXT NOT NULL`, `schema_version INTEGER NOT NULL`
- `payload JSONB NOT NULL`, `created_at TIMESTAMPTZ NOT NULL`
- `published_at TIMESTAMPTZ`, `publish_attempts INTEGER NOT NULL DEFAULT 0`
- Index `(created_at)` for unpublished events. Retain/archive published rows
  according to the operational retention policy.

### `job_events`

- `id BIGSERIAL PRIMARY KEY`, `tenant_id UUID NOT NULL`, `job_id UUID NOT NULL`
- `event_type TEXT NOT NULL`, `from_status TEXT`, `to_status TEXT`
- `attempt_number INTEGER`, `details JSONB`, `created_at TIMESTAMPTZ NOT NULL`
- Index `(tenant_id, job_id, id)` for ordered history.

PostgreSQL constraints and transactions enforce correctness. Redis keys may
accelerate rate limiting, short leases, or cache reads, but PostgreSQL lease
and version checks remain the fencing authority.

The initial job API is single-tenant: the first migration omits tenant and
workflow foreign keys because authentication and workflow definitions are
not yet implemented. The schema above is the target model; tenant scoping and
workflow references must be introduced through migrations before those
features are exposed. Attempt, result/error, event, idempotency, and outbox
tables are likewise future additive migrations; the current `jobs` table
contains the core persisted fields required by the first REST API.

## 6. API contracts

All routes are versioned under `/api/v1` and use JSON. Tenant authentication
is a production-readiness requirement and is not implemented in the current
single-tenant API. Errors use RFC-style Problem Details (`type`, `title`,
`status`, `detail`, `instance`, and a stable `code` extension); validation
errors also include field-level details. Current job listing uses bounded
offset pagination and a server-side sort-field allowlist.

### Implemented job routes and planned additions

| Method and path | Purpose | Success |
| --- | --- | --- |
| `POST /api/v1/workflows` | Create immutable workflow version (planned) | `201` |
| `GET /api/v1/workflows/{workflowId}` | Read workflow/version (planned) | `200` |
| `POST /api/v1/jobs` | Create an immediate or scheduled job | `201` |
| `GET /api/v1/jobs/{jobId}` | Read current job state/result | `200` |
| `GET /api/v1/jobs` | List jobs with filters and bounded pagination | `200` |
| `POST /api/v1/jobs/{jobId}/cancel` | Cancel an eligible job | `200` |
| `GET /api/v1/jobs/{jobId}/attempts` | Read ordered attempt history (planned) | `200` |

The current create request accepts `jobType`, JSON-object `payload`, optional
`scheduledAt`, `maxAttempts` (default 5, 1–100), and `retryDelaySeconds`
(default 0, 0–86400). It returns `201 Created`, a `Location` header, and a job
DTO. Future schedule times produce `SCHEDULED`; absent or past times produce
`PENDING`. Listing supports optional status and exact job type filters,
zero-based `page`, bounded `size` (1–100), an allowlisted `sortBy`, and
`ASC`/`DESC` direction. Cancelling `PENDING` or `SCHEDULED` transitions it
immediately to `CANCELLED`; repeated cancellation of an already-cancelled job
is idempotent. Other ineligible states return `409 Conflict`. Unknown jobs
return `404`; malformed input and unsupported sorting return `400`.

Request idempotency and workflow-linked submission remain planned work; no
Kafka dispatch is performed when creating a job in this milestone.

### Planned event envelopes

Kafka events carry `eventId`, `eventType`, `schemaVersion`, `occurredAt`,
`tenantId`, `aggregateId`, and a typed payload. Dispatch is partitioned by
`jobId` to preserve per-job order. Consumers keep an inbox/deduplication
record or perform an atomic state/version transition so replayed event IDs do
not apply a transition twice.

## 7. Job lifecycle and worker execution

### States

```text
QUEUED -> RUNNING -> SUCCEEDED
                  -> RETRY_WAIT -> QUEUED
                  -> FAILED
                  -> CANCELLED
QUEUED -> CANCELLED
RUNNING -> CANCEL_REQUESTED -> CANCELLED
```

`RETRY_WAIT` is durable and records `run_at`; it transitions to `QUEUED` when
eligible. `FAILED`, `SUCCEEDED`, and `CANCELLED` are terminal. Cancellation
is cooperative for running work: a worker is notified and given a bounded
grace period; hard process termination is an infrastructure action, not a
guarantee that external side effects were undone.

### Execution protocol

1. API validates and atomically inserts the job, idempotency mapping, initial
   job event, and outbox event. It responds accepted only after commit.
2. Scheduler claims eligible rows using a short database transaction and
   publishes an at-least-once dispatch event through the outbox publisher.
3. Worker consumes the event, atomically claims/creates the next attempt,
   transitions the job to `RUNNING`, and receives a unique lease token and
   expiry. Stale tokens cannot complete a newer attempt.
4. Worker heartbeats at a fraction of the lease interval. Execution handlers
   receive the job ID, attempt number, cancellation signal, and recommended
   downstream idempotency key.
5. Completion or failure is committed only if the attempt and lease token are
   current. Job state, attempt, history, and any resulting outbox event change
   in one transaction.
6. If the worker disappears, lease expiry makes the attempt recoverable.
   Recovery records the lost attempt and retries if policy allows; otherwise
   it transitions to `FAILED`.

## 8. Retry, idempotency, and failure recovery

- Retry only classified transient failures; validation and permanent handler
  errors fail without retry unless workflow policy explicitly says otherwise.
- Default planned policy: maximum 5 attempts, exponential delay
  `min(initialDelay * 2^(attempt-1), maxDelay)` plus bounded random jitter.
  Defaults remain configuration policy until product-level limits are set.
- Persist `attempt_count`, next `run_at`, failure class, and last error before
  acknowledging a failed attempt. A retry is a new attempt, never an in-place
  rewrite of attempt history.
- Enforce request idempotency with a unique tenant/scope/key record and
  canonical request hash. Preserve mappings for a documented retention window.
- Kafka delivery and outbox publication are at least once. Event consumers
  deduplicate and apply guarded transitions. Handler side effects require a
  stable downstream idempotency key (typically `jobId` plus effect name).
- Use PostgreSQL transactions for job/attempt/outbox consistency, lease
  fencing for stale workers, and a reconciler to find overdue leases and
  unpublished outbox events.
- On Kafka unavailability, keep committed outbox rows and retry publication
  with backoff. On Redis unavailability, fall back to database-backed
  correctness paths and degrade optional caching/rate limiting explicitly.
- Poison messages are boundedly retried, then moved to a dead-letter topic
  with original event metadata; alert and provide an operator replay path.
- Retain failure details safely: redact secrets, cap payload sizes, and apply
  tenant-aware retention to job inputs/results/events.

## 9. Security and operations baseline

Use TLS between deployed services and authenticated principals at public
boundaries. Store secrets in environment/secret-manager configuration, never
in source control. Validate tenant ownership on every object operation.
Apply payload limits, request timeouts, database connection pooling, Kafka
consumer backpressure, and explicit shutdown/drain behavior. Readiness should
represent required dependencies; liveness should not fail merely because an
optional cache is unavailable. Add structured logs with correlation IDs and
avoid logging job inputs or credentials.

## 10. Delivery roadmap

Each milestone is independently reviewable and should have targeted tests,
updated progress documentation, and a Conventional Commit.

1. **Foundation (complete):** monorepo conventions, architecture and contracts
   documentation, Java 21 Maven reactor, and Spring Boot health endpoint.
2. **PostgreSQL persistence and job management API (complete):**
   PostgreSQL Compose configuration, Flyway-managed jobs schema, JPA entity
   and repository, job lifecycle, REST DTOs/endpoints, validation, Problem
   Details, OpenAPI, and PostgreSQL Testcontainers coverage.
3. **Idempotent submission and outbox:** request idempotency, transactional
   outbox, event schemas, publisher, duplicate/restart integration tests.
4. **Scheduling and Kafka dispatch:** due-work claiming, backpressure,
   partition strategy, retry scheduling, dead-letter handling and metrics.
5. **Worker runtime and recovery:** handler contract, attempt/lease fencing,
   heartbeat, cancellation, transient/permanent failure classification,
   crash/lease-expiry recovery tests.
6. **Operator UI:** React/TypeScript console, typed API client, job listing,
   detail and attempt timeline, cancel/retry operations with accessibility and
   UI tests.
7. **Production readiness:** tenant authentication/authorization, quotas,
   retention, Kafka/Redis/Prometheus/Grafana local and deployment setup, load
   and resilience tests, runbooks, and security review.

Do not advance a milestone solely because its code exists: its acceptance
checks must run and its status in `docs/PROGRESS.md` must be updated.
