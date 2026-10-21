# FlowForge Architecture and Implementation Plan

## 1. Goals and scope

FlowForge orchestrates durable, asynchronous jobs with explicit state,
bounded retries, safe worker recovery, and operational visibility. The
initial increment established the repository structure and Java 21 Spring
Boot control plane; the current foundation also includes job management,
PostgreSQL persistence, Kafka outbox dispatch, a separately scalable worker
runtime, and a React/TypeScript operator dashboard. Workflow definitions,
tenant isolation and production infrastructure remain future work. Milestone
7A adds single-tenant OIDC user authentication and per-user job ownership; it
does not implement tenant boundaries.

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

The Maven reactor currently contains the root parent plus
`services/control-plane` and `services/worker`. Shared code should only be
extracted after a concrete cross-service reuse case appears; PostgreSQL schema
migrations remain packaged and applied by the control plane.

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

Provides API-backed overview counts, server-paginated job listing, job
submission, polled job detail, and cancellation. It calls the versioned
control-plane API and does not connect directly to PostgreSQL, Kafka, or Redis.
The current response DTO does not expose execution result data or attempt
history; the UI states this limitation and only builds its timeline from
timestamps actually returned by the API.

### Platform observability

Each service exports health and metrics endpoints. Prometheus scrapes the
services; Grafana dashboards visualize request latency, queue lag, dispatch
age, job outcomes, retry counts, lease expiry, and worker utilization. Avoid
job IDs, tenant IDs, and other high-cardinality values as metric labels.

## 5. Data model

Use PostgreSQL Flyway migrations and UTC timestamps. The long-term target
includes explicit `tenant_id` boundaries; the currently implemented API is a
single-tenant user model with UUID identifiers exposed as opaque IDs.

### `workflows`

- `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL`
- `name TEXT NOT NULL`, `definition JSONB NOT NULL`
- `version INTEGER NOT NULL`, `created_at`, `updated_at`
- Unique `(tenant_id, name, version)`; definition is immutable per version.

### `jobs`

- `id UUID PRIMARY KEY`
- `owner_issuer TEXT`, `owner_subject TEXT`; both null for legacy records or
  both non-null for jobs created by an authenticated user.
- `workflow_id UUID`, `workflow_version INTEGER`
- `status TEXT NOT NULL`, `input JSONB`, `result JSONB`, `error JSONB`
- `priority INTEGER NOT NULL DEFAULT 0`, `run_at TIMESTAMPTZ NOT NULL`
- `attempt_count INTEGER NOT NULL DEFAULT 0`, `max_attempts INTEGER NOT NULL`
- `idempotency_key TEXT`, `created_at`, `updated_at`, `started_at`,
  `finished_at`, `cancel_requested_at`
- `version BIGINT NOT NULL DEFAULT 0` for compare-and-set transitions.
- Existing indexes support due work and status/creation-time job listing.
  Owner filtering is applied to database queries before pagination.

### `job_attempts`

- `id UUID PRIMARY KEY`, `job_id UUID NOT NULL`, `attempt_number INTEGER`
- `status TEXT NOT NULL`, `worker_id TEXT`, `fencing_token BIGINT NOT NULL`
- `lease_expires_at TIMESTAMPTZ`, `started_at`, `heartbeat_at`, `finished_at`
- `error JSONB`, `result JSONB`, `created_at`
- Unique `(job_id, attempt_number)`; index on `(status, lease_expires_at)`.

Implemented V4 execution fields on `jobs` include `lease_owner`,
`lease_expires_at`, monotonic `fencing_token`, due `next_attempt_at`,
`cancel_requested_at`, `result`, and `error`. The `control-plane` owns Flyway
migrations for the shared database; workers must start only after it has
applied the current schema.

### `idempotency_records`

- `operation_name TEXT NOT NULL`, `principal_issuer TEXT NOT NULL`,
  `principal_subject TEXT NOT NULL`, `idempotency_key TEXT NOT NULL`
- `request_fingerprint CHAR(64) NOT NULL`, `job_id UUID NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`, `expires_at TIMESTAMPTZ`
- Primary key `(operation_name, principal_issuer, principal_subject,
  idempotency_key)`. Existing records are preserved in the `legacy` identity
  scope; new users cannot replay them. Reusing a key with different request
  content conflicts.

### `outbox_events`

- `id UUID PRIMARY KEY`, `aggregate_id UUID NOT NULL`
- `event_type TEXT NOT NULL`, `schema_version INTEGER NOT NULL`
- `payload JSONB NOT NULL`, `created_at TIMESTAMPTZ NOT NULL`
- `published_at TIMESTAMPTZ`, `attempt_count INTEGER NOT NULL DEFAULT 0`,
  `next_attempt_at TIMESTAMPTZ NOT NULL`, `last_error TEXT`
- `claimed_by`, `claim_expires_at` support leased multi-publisher claims;
  `terminal_failure_at` and `cancelled_at` distinguish terminal outcomes
  without representing them as broker-published.
- Partial ready index on `(next_attempt_at, created_at)`; published and
  terminal events are retained/archived according to operational policy.

### `job_events`

- `id BIGSERIAL PRIMARY KEY`, `tenant_id UUID NOT NULL`, `job_id UUID NOT NULL`
- `event_type TEXT NOT NULL`, `from_status TEXT`, `to_status TEXT`
- `attempt_number INTEGER`, `details JSONB`, `created_at TIMESTAMPTZ NOT NULL`
- Index `(tenant_id, job_id, id)` for ordered history.

PostgreSQL constraints and transactions enforce correctness. Redis keys may
accelerate rate limiting, short leases, or cache reads, but PostgreSQL lease
and version checks remain the fencing authority.

The current job API has user-level ownership but no tenant boundary. Flyway
V5 additively introduced nullable job owners and user-scoped idempotency
records without removing historical jobs. Existing unowned jobs are
administrator-only. Explicit `tenant_id` columns and tenant-scoped
authorization remain future migrations. Workflow foreign keys and workflow
tables also remain future work. The current schema has additive Flyway
migrations for jobs, idempotency, outbox events, and worker attempts/leases.

## 6. API contracts

All routes are versioned under `/api/v1` and use JSON. Except public health
probes, API operations require a Keycloak-issued bearer access token validated
for signature, issuer, audience, and timestamps. ADMIN can access all jobs;
OPERATOR and VIEWER can read only their own jobs, and only OPERATOR can submit
or cancel. Errors use RFC-style Problem Details (`type`, `title`, `status`,
`detail`, `instance`, and a stable `code` extension); authentication and
authorization failures return generic 401 and 403 responses. Current job
listing uses bounded offset pagination and a server-side sort-field allowlist.

### Implemented job routes and planned additions

| Method and path | Purpose | Success |
| --- | --- | --- |
| `POST /api/v1/workflows` | Create immutable workflow version (planned) | `201` |
| `GET /api/v1/workflows/{workflowId}` | Read workflow/version (planned) | `200` |
| `POST /api/v1/jobs` | Create an immediate or scheduled job | `201` |
| `GET /api/v1/jobs/{jobId}` | Read current job state, payload, and exposed timestamps | `200` |
| `GET /api/v1/jobs` | List jobs with filters and bounded pagination | `200` |
| `POST /api/v1/jobs/{jobId}/cancel` | Cancel an eligible job | `200` |
| `GET /api/v1/jobs/{jobId}/attempts` | Read ordered attempt history (planned) | `200` |

The current `JobResponse` exposes `id`, `jobType`, `payload`, `status`,
creation/update/schedule/start/completion/cancellation timestamps, attempt
count and retry configuration, and optimistic-lock version. It does not expose
the persisted worker result or attempt history; those API capabilities remain
planned. The current create request accepts `jobType`, JSON-object `payload`, optional
`scheduledAt`, `maxAttempts` (default 5, 1–100), and `retryDelaySeconds`
(default 0, 0–86400). It returns `201 Created`, a `Location` header, and a job
DTO. Future schedule times produce `SCHEDULED`; absent or past times produce
`PENDING`. An optional `Idempotency-Key` header, limited to 255 non-blank
characters, makes repeated equivalent submissions return the original job.
The key is scoped to `POST:/api/v1/jobs`; mismatched request content returns
`409 Conflict`. Records are retained for 24 hours and may then be reused.
Expired records are cleaned hourly. Keys are scoped to the operation and
validated user `(iss, sub)`; this is user isolation, not tenant scoping.
Explicit tenant scope is required before multi-tenant deployment.

Listing supports optional status and exact job type filters, zero-based
`page`, bounded `size` (1–100), an allowlisted `sortBy`, and `ASC`/`DESC`
direction. Cancelling `PENDING`, `SCHEDULED`, or `DISPATCHING` transitions it
immediately to `CANCELLED`; repeated cancellation of an already-cancelled job
is idempotent. Other ineligible states return `409 Conflict`. Unknown jobs
return `404`; malformed input and unsupported sorting return `400`.

Each successful job submission and its `JobCreated` JSONB outbox event are
persisted atomically. The outbox publisher claims bounded batches using
`FOR UPDATE SKIP LOCKED`, a publisher ID, and an expiring lease. Claiming and
status changes happen in a short PostgreSQL transaction; Kafka I/O occurs
after commit. An event is marked published only after broker acknowledgement.
If a process crashes after acknowledgement but before the PostgreSQL commit,
the event can be delivered again.

The local broker is Apache Kafka 3.9.1 in KRaft mode. V1 events use topic
`flowforge.job-dispatch.v1`, key `aggregateId` (the job UUID), and a JSON
envelope defined in
[`libs/contracts/events/job-created-v1.schema.json`](../libs/contracts/events/job-created-v1.schema.json).
Key-based partitioning preserves per-job ordering while partition assignment
remains stable. Compatible changes may add optional fields without changing
the v1 contract; breaking changes require a new schema and topic version.
Kafka delivery is at least once, not exactly once.

Jobs enter `DISPATCHING` while a claim is active and move to `DISPATCHED` only
after acknowledgement has been recorded. Neither state means a worker has
begun or completed execution. Future-dated `SCHEDULED` jobs are selected only
when `scheduledAt` is due. Cancellation atomically marks the job and
suppresses an unpublished event, including while it is claimed. If cancellation
races a broker send already in progress, Kafka may still accept the message;
consumers must check PostgreSQL job state before execution.

Publication failures use exponential backoff (defaults: 1 second initial,
5-minute maximum, 8 total attempts). Attempts count claims, including claims
whose publisher crashed; expired final claims become terminal. Terminal
publication failure marks the job `FAILED` and the event
`terminal_failure_at` without setting `published_at`. Micrometer exposes
pending event count, publication and terminal failure counters, successful
publication count, and dispatch latency.

### Event envelope and consumer expectations

Kafka events carry `eventId`, `eventType`, `schemaVersion`, `occurredAt`,
`aggregateId`, and a typed payload. The current single-tenant v1 contract does
not include `tenantId`; tenant identity must be added through a compatible
contract or version before multi-tenant use. Consumers keep an
inbox/deduplication record or perform an atomic state/version transition so
replayed event IDs do not apply a transition twice. Before starting a job, the
worker confirms through its atomic PostgreSQL claim that the job remains
eligible and has not been cancelled.

## 7. Job lifecycle and worker execution

### States

```text
PENDING / due SCHEDULED -> DISPATCHING -> DISPATCHED -> RUNNING -> COMPLETED
                                  |              |         |-> DISPATCHED (retry due later)
                                  |              |         |-> FAILED
                                  |              |         |-> CANCELLED
                                  |-> CANCELLED (cancel before publication ack)
                                  |-> PENDING / SCHEDULED (publication retry)
                                  |-> FAILED (publication attempts exhausted)
PENDING / SCHEDULED -> CANCELLED
```

`PENDING` and `SCHEDULED` represent work accepted by the API but not yet
acknowledged by Kafka. `DISPATCHING` means the publisher has a live claim;
`DISPATCHED` means Kafka acknowledged the dispatch message and the outbox
publication was recorded. Neither means the worker started or succeeded.
Publication retry returns a job to `PENDING` or `SCHEDULED`; exhausted
publication attempts mark it `FAILED` without claiming that execution ran.
Execution retries keep the job in `DISPATCHED` and persist their future
eligibility in `next_attempt_at`; the attempt row is `RETRY_WAIT`. Running
cancellation sets `cancel_requested_at` while preserving `RUNNING`, so the API
can report a pending cancellation rather than falsely reporting that execution
has stopped. A cooperative handler observes the request and transitions the
job to `CANCELLED`. A cancel racing with a send already in flight cannot
retract a Kafka-accepted message; workers check authoritative PostgreSQL state
before claiming it.

### Execution protocol

1. API validates and atomically inserts the job, idempotency mapping, initial
   job event, and outbox event. It responds accepted only after commit.
2. Outbox publisher claims eligible rows using a short database transaction,
   commits the lease, and publishes an at-least-once dispatch event.
3. Worker consumes the event and atomically claims a due `DISPATCHED` job,
   transitions it to `RUNNING`, increments the monotonic fencing token, and
   inserts a unique attempt row with worker ID and lease. A duplicate event
   cannot claim an active or terminal job.
4. Worker heartbeats renew unexpired leases. Handlers receive job ID, attempt
   number, fencing token, and cooperative cancellation check. Handler code
   runs outside database transactions.
5. Completion or failure is committed only if worker ID and fencing token are
   current. Job state and attempt result/failure are changed in one
   PostgreSQL transaction. The Kafka record is committed after listener
   processing; if a worker crashes, a PostgreSQL recovery scan independently
   finds due retries and expired leases.
6. Recovery marks the prior attempt abandoned and increments the fencing
   token. The old worker can no longer update FlowForge state; it may still
   have performed an external side effect before losing its lease.

## 8. Retry, idempotency, and failure recovery

- Retry only classified transient failures; unsupported job types and explicit
  permanent handler errors fail without retry. Unexpected handler exceptions
  are treated as transient.
- The effective worker attempt limit is the minimum of the job's
  `max_attempts` and the worker's `FLOWFORGE_WORKER_MAX_ATTEMPTS` cap. The
  current worker default cap is 100; newly submitted jobs default to 5.
  Exponential retry delay is capped by `FLOWFORGE_WORKER_RETRY_MAX_DELAY`
  (default 5 minutes), with the initial delay set to the larger of the job's
  `retryDelaySeconds` and worker's configured retry base. No jitter is applied.
- Outbox publication independently defaults to 8 claim attempts with
  exponential retry from 1 second up to 5 minutes; it has no jitter yet.
- Persist `attempt_count`, `next_attempt_at`, failure class, and error before
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
- Poison or unsupported event contracts require operational intervention;
  a dead-letter topic and replay tooling are not implemented. PostgreSQL's
  recovery scan remains the authority for already-persisted eligible jobs.
- Retain failure details safely: redact secrets, cap payload sizes, and apply
  tenant-aware retention to job inputs/results/events.

## 9. Security and operations baseline

The current user-authentication design, role matrix, local setup, threat
considerations, and verification boundaries are documented in
`docs/SECURITY_ARCHITECTURE.md` and `docs/AUTHENTICATION_SETUP.md`. Use TLS
between deployed services and authenticated principals at public boundaries.
Store secrets in environment/secret-manager configuration, never in source
control. Both backend services require explicit database username and password
configuration; local setup supplies these through the ignored `.env` file and
the application no longer defaults missing values to empty strings. Validate
user ownership on every object operation; tenant ownership remains a future
isolation boundary.
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
3. **Idempotent submission and outbox (complete):** operation-scoped request
   idempotency with defined retention, transactional `JobCreated` outbox,
   database-safe concurrent submission, and PostgreSQL integration tests.
   The publisher and Kafka delivery were added in milestone 4.
4. **Scheduling and Kafka dispatch (complete):** local Kafka, versioned job
   event contract, leased `SKIP LOCKED` outbox publisher, due-time eligibility,
   bounded retry/terminal failure, cancellation handling, metrics, and
   PostgreSQL/Kafka Testcontainers integration tests. Worker consumption and
   execution were delivered in milestone 5.
5. **Worker runtime and recovery (complete):** independent Spring Boot worker,
   versioned Kafka consumer, handler registry and demo handlers,
   PostgreSQL-backed attempt history and lease/fencing, heartbeat, cooperative
   cancellation, transient/permanent failure classification, bounded retries,
   crash/lease-expiry recovery, and PostgreSQL/Kafka Testcontainers tests.
6. **Operator UI (complete):** React/TypeScript Vite dashboard with typed API
   client, API-backed overview, server-side job filtering/sorting/pagination,
   submission, live detail polling, cancellation, and accessible responsive
   dark UI. Vitest and Testing Library cover API status handling, form
   validation/submission, and detail behavior. The detail timeline is limited
   to timestamps in the current DTO; attempt history, results, and retry
   actions remain unavailable until the API exposes them. Live backend
   verification and genuine screenshots are recorded in the README.
7A. **User authentication and authorization (complete):** Keycloak OIDC
   Authorization Code + PKCE for the SPA, Spring Security JWT resource server,
   ADMIN/OPERATOR/VIEWER authorization, and user-owned jobs derived from
   validated `(iss, sub)` claims. Existing unowned rows remain ADMIN-only.
   Clean Maven/Testcontainers verification, Compose validation, PostgreSQL
   ownership tests, and live OIDC/API/worker checks were verified. A separate
   Maven dependency vulnerability scan and production identity-provider and
   infrastructure hardening remain follow-up work; see `docs/PROGRESS.md`.
   FLOW-103 additionally validates nonblank database credentials before
   datasource initialization in both services.
7B. **Production readiness and tenant isolation (planned):** explicit tenant
   identity and data boundaries, tenant-scoped quotas, retention and audit
   policy, Kafka/Redis/Prometheus/Grafana deployment, load and resilience
   tests, production identity-provider configuration, and runbooks.

Do not advance a milestone solely because its code exists: its acceptance
checks must run and its status in `docs/PROGRESS.md` must be updated.
