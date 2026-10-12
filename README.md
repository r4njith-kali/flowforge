# FlowForge

FlowForge is a distributed workflow orchestration platform for defining,
submitting, scheduling, and observing durable background jobs. The control
plane uses Java 21 and Spring Boot with PostgreSQL persistence, a versioned job
management API, and transactional outbox publication to Apache Kafka. A
separate Spring Boot worker service consumes dispatch events and executes
registered job handlers. Redis and the React/TypeScript operator UI remain
planned components.

## Architecture at a glance

The control plane exposes job creation, listing, detail, and cancellation
endpoints. Flyway manages the PostgreSQL schema; the API persists jobs and
returns DTOs, not JPA entities. PostgreSQL remains the source of truth. A
database-backed outbox publisher claims eligible events and delivers them to
Kafka; independently scalable workers claim execution attempts and persist
results in PostgreSQL. Redis is planned only for short-lived coordination and
caching.

See [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) for the
architecture, service boundaries, data model, lifecycle, and roadmap.
Milestone status is tracked in [docs/PROGRESS.md](docs/PROGRESS.md).

## Repository layout

```text
apps/
  web/                         React + TypeScript operator console (planned)
services/
  control-plane/               Spring Boot HTTP API and PostgreSQL job store
  scheduler/                   Scheduling currently runs in control-plane outbox polling
  worker/                      Kafka consumer, handler registry, execution and recovery
libs/
  contracts/events/            Versioned Kafka event JSON schemas
  java-common/                 Shared Java primitives (planned)
infra/
  docker/                      PostgreSQL, Kafka, and worker local Compose configuration
  monitoring/                  Prometheus/Grafana configuration (planned)
docs/
  IMPLEMENTATION_PLAN.md
  PROGRESS.md
```

## Prerequisites

- JDK 21
- Apache Maven 3.9 or newer
- Docker Engine with Docker Compose v2 (for PostgreSQL and Testcontainers)

The current development environment has OpenJDK 25.0.1 rather than JDK 21;
Maven compiles both services with `--release 21`. A JDK 21 runtime was not
available for direct execution testing here.

## Start PostgreSQL and Kafka locally

From the repository root, create a local environment file and set a private
development password:

```bash
cp .env.example .env
# Edit POSTGRES_PASSWORD and DB_PASSWORD in .env to the same local value.
docker compose --env-file .env -f infra/docker/compose.yaml up -d postgres kafka
set -a
source .env
set +a
```

`.env` is ignored by Git. Compose requires `POSTGRES_DB`, `POSTGRES_USER`, and
`POSTGRES_PASSWORD`; the application reads `DB_URL`, `DB_USERNAME`, and
`DB_PASSWORD`. Kafka uses `KAFKA_BOOTSTRAP_SERVERS` (default
`localhost:9092`). The example file contains placeholders only. Flyway applies
the versioned schema on application startup, and Hibernate validates mappings
without generating or modifying schema.

## Build, test, and run

From the repository root:

```bash
mvn -B -ntp verify
mvn -pl services/control-plane spring-boot:run
```

The test suite includes unit, MVC-slice, PostgreSQL Testcontainers, and Kafka
Testcontainers integration tests, so Docker must be available for `verify`.
Testcontainers negotiates the Docker API by default. On a local daemon that
requires a compatibility pin, use
`mvn -B -ntp clean verify -Dtestcontainers.docker-api-version=1.40`; this
optional Maven profile only affects Surefire tests. CI should use normal Docker
API negotiation and must not depend on this local override.

Start the control plane once so Flyway applies the schema migrations (including
worker attempt and lease tables):

```bash
mvn -pl services/control-plane spring-boot:run
```

The control plane binds to port `8080`. Check readiness and API documentation:

```bash
curl http://localhost:8080/actuator/health
open http://localhost:8080/swagger-ui/index.html
```

The OpenAPI JSON is available at `/v3/api-docs`. The worker service uses the
same database schema, so start it only after the control plane has applied the
migrations. Run a local worker in another terminal with
`mvn -pl services/worker spring-boot:run`, or scale Compose workers:

```bash
docker compose --env-file .env -f infra/docker/compose.yaml \
  up --build -d --scale worker=3 worker
```

Compose gives container workers the internal Kafka listener
(`kafka:29092`) and PostgreSQL service address; it retains `localhost:9092`
for host-based applications.

## Job API

All routes use `/api/v1` and JSON:

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/v1/jobs` | Create a job; returns `201 Created` and a `Location` header |
| `GET` | `/api/v1/jobs` | List jobs with optional status/type filters and bounded pagination |
| `GET` | `/api/v1/jobs/{id}` | Retrieve a job |
| `POST` | `/api/v1/jobs/{id}/cancel` | Cancel eligible work; running jobs receive a cooperative cancellation request |

Create request fields are `jobType` (required, 1–200 characters), `payload`
(required JSON object), optional `scheduledAt`, `maxAttempts` (default 5,
range 1–100), and `retryDelaySeconds` (default 0, range 0–86400). A future
`scheduledAt` creates a `SCHEDULED` job; an immediate or past schedule creates
a `PENDING` job. The optional `Idempotency-Key` header makes repeated
equivalent submissions return the original job; using the same key with
different content returns `409 Conflict`. Keys are scoped to
`POST:/api/v1/jobs` and retained for 24 hours. This API is currently
single-tenant: tenant scoping must be added before multi-tenant deployment.
Cancellation is idempotent for already-cancelled jobs and remains available
while a job is pending, scheduled, or in the publisher's `DISPATCHING` phase.
For a running job it records `cancelRequestedAt` and returns `202 Accepted`;
the job remains `RUNNING` until its handler cooperatively stops, and only then
does it become `CANCELLED`. Cancellation after dispatch but before a worker
starts remains a `409 Conflict`. State changes use optimistic locking and
guarded database updates.

List parameters include `status`, exact `jobType`, zero-based `page` (default
0), `size` (default 20, maximum 100), `sortBy` (`createdAt`, `updatedAt`,
`scheduledAt`, `jobType`, or `status`), and `direction` (`ASC` or `DESC`).
Errors use RFC-style Problem Details with a stable `code`; validation errors
also include field-level details.

Job creation and its `JobCreated` event are recorded atomically in PostgreSQL.
The publisher claims bounded batches with `FOR UPDATE SKIP LOCKED`, commits
the claim before network I/O, and marks events published only after Kafka
acknowledges delivery. Claims expire for crash recovery; publication retries
use bounded exponential backoff and become terminal after eight attempts by
default. Future-dated jobs are not claimable until their scheduled time.
The publisher defaults to a 50-event batch, one-second poll interval, one
minute claim lease, and ten-second Kafka send timeout. Configure these with
`FLOWFORGE_OUTBOX_BATCH_SIZE`, `FLOWFORGE_OUTBOX_POLL_INTERVAL_MS`,
`FLOWFORGE_OUTBOX_CLAIM_LEASE`, and `FLOWFORGE_KAFKA_SEND_TIMEOUT`;
`FLOWFORGE_OUTBOX_MAX_ATTEMPTS`, `FLOWFORGE_OUTBOX_RETRY_BASE_DELAY`, and
`FLOWFORGE_OUTBOX_RETRY_MAX_DELAY` control retry limits and backoff.

Kafka messages use topic `flowforge.job-dispatch.v1`, a versioned JSON envelope
([schema](libs/contracts/events/job-created-v1.schema.json)), and the job UUID
as the message key. The key groups a job's messages on one partition; the
local broker defaults to three partitions. Job status becomes `DISPATCHED`
after broker acknowledgement and remains distinct from `RUNNING` or
`COMPLETED`. Delivery is at least once: an acknowledged message may be
delivered again if the process crashes before persisting `published_at`.
Consumers must dedupe by `eventId` and confirm current job state before
execution. A cancellation racing an already-started broker send cannot retract
a message already accepted by Kafka; consumers must not execute a job whose
PostgreSQL state is `CANCELLED`. Exactly-once distributed execution and
exactly-once external side effects are not guaranteed.

Micrometer exports `flowforge.outbox.pending`,
`flowforge.outbox.published`, `flowforge.outbox.publication.failures`,
`flowforge.outbox.terminal.failures`, and
`flowforge.job.dispatch.latency`.

## Worker execution

The separate Java 21/Spring Boot worker service consumes
`JobCreated` schema v1 from `flowforge.job-dispatch.v1` as a dispatch hint. It
loads the authoritative job record from PostgreSQL, then atomically claims an
eligible `DISPATCHED` job or an expired `RUNNING` attempt using row locks.
Each claim increments a monotonically increasing fencing token and writes a
`job_attempts` row with worker ID and lease timestamps. PostgreSQL transactions
end before handler code runs. Heartbeats renew active leases, and a database
recovery scan reclaims expired work and due retries even if a Kafka message was
already acknowledged.

Built-in demonstrations are `demo.echo` and `demo.delay` (the latter accepts
`delayMillis` up to 30,000). Implementations register a `JobHandler` for each
job type. Handlers should be idempotent or use downstream idempotency keys,
check the provided cancellation context at safe points, and avoid unbounded
execution. Unexpected exceptions and explicitly transient
`JobExecutionException`s are retried; permanent errors, including unsupported
job types, fail without retry. The effective attempt limit is the lower of
the per-job `maxAttempts` and `FLOWFORGE_WORKER_MAX_ATTEMPTS` (default 100).
Retries use exponential backoff capped at five minutes by default, with the
larger of the job's `retryDelaySeconds` and
`FLOWFORGE_WORKER_RETRY_BASE_DELAY` as the initial delay.

The job remains `DISPATCHED` while waiting for a retry; its individual attempt
is marked `RETRY_WAIT`. A worker lease expiry marks the old attempt
`ABANDONED` and may start another attempt with a higher fencing token. Results,
failures, attempt timestamps, and worker ownership are persisted. Worker
metrics include execution starts/completions/failures/retries, terminal
failures, fenced completions, and execution duration; management endpoints
are available on port `8081`.

Kafka and job execution are at least once, not exactly once. A worker can
perform an external side effect and crash before recording completion; the
job may run again. Fencing protects FlowForge's PostgreSQL state, not external
systems. Cancellation is cooperative: the API reports the outstanding request
with HTTP `202`, but a custom handler that does not check its context may
continue until it returns or loses its lease. The control plane owns Flyway
migrations; apply them before starting workers. Workflow definitions,
authentication, tenant isolation, Redis, and provisioned Prometheus/Grafana
dashboards remain future work.
