# FlowForge

FlowForge is a distributed workflow orchestration platform for defining,
submitting, scheduling, and observing durable background jobs. The control
plane uses Java 21 and Spring Boot with PostgreSQL persistence and a versioned
job management API. Kafka, Redis, and the React/TypeScript operator UI remain
planned components; Kafka workers are intentionally out of scope for the
current milestone.

## Architecture at a glance

The control plane exposes job creation, listing, detail, and cancellation
endpoints. Flyway manages the PostgreSQL schema; the API persists jobs and
returns DTOs, not JPA entities. The target architecture separates this API
from a future scheduler/dispatcher and worker runtime. PostgreSQL remains the
source of truth; Kafka will provide asynchronous work distribution in a later
milestone. Redis is planned only for short-lived coordination and caching.

See [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) for the
architecture, service boundaries, data model, lifecycle, and roadmap.
Milestone status is tracked in [docs/PROGRESS.md](docs/PROGRESS.md).

## Repository layout

```text
apps/
  web/                         React + TypeScript operator console (planned)
services/
  control-plane/               Spring Boot HTTP API and PostgreSQL job store
  scheduler/                   Durable scheduling and Kafka dispatch (planned)
  worker/                      Worker runtime and execution adapters (planned)
libs/
  contracts/                   Shared API/event schemas (planned)
  java-common/                 Shared Java primitives (planned)
infra/
  docker/                      PostgreSQL local Compose configuration
  monitoring/                  Prometheus/Grafana configuration (planned)
docs/
  IMPLEMENTATION_PLAN.md
  PROGRESS.md
```

## Prerequisites

- JDK 21
- Apache Maven 3.9 or newer
- Docker Engine with Docker Compose v2 (for PostgreSQL and Testcontainers)

## Start PostgreSQL locally

From the repository root, create a local environment file and set a private
development password:

```bash
cp .env.example .env
# Edit POSTGRES_PASSWORD and DB_PASSWORD in .env to the same local value.
docker compose -f infra/docker/compose.yaml up -d postgres
set -a
source .env
set +a
```

`.env` is ignored by Git. Compose requires `POSTGRES_DB`, `POSTGRES_USER`, and
`POSTGRES_PASSWORD`; the application reads `DB_URL`, `DB_USERNAME`, and
`DB_PASSWORD`. The example file contains placeholders only. Flyway applies
the versioned schema on application startup, and Hibernate validates mappings
without generating or modifying schema.

## Build, test, and run

From the repository root:

```bash
mvn -B -ntp verify
mvn -pl services/control-plane spring-boot:run
```

The test suite includes unit, MVC-slice, and PostgreSQL Testcontainers
integration tests, so Docker must be available for `verify`. The local test
configuration pins the Docker API level to one supported by Testcontainers.

The control plane binds to port `8080`. Check readiness and API documentation:

```bash
curl http://localhost:8080/actuator/health
open http://localhost:8080/swagger-ui/index.html
```

The OpenAPI JSON is available at `/v3/api-docs`.

## Job API

All routes use `/api/v1` and JSON:

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/v1/jobs` | Create a job; returns `201 Created` and a `Location` header |
| `GET` | `/api/v1/jobs` | List jobs with optional status/type filters and bounded pagination |
| `GET` | `/api/v1/jobs/{id}` | Retrieve a job |
| `POST` | `/api/v1/jobs/{id}/cancel` | Cancel a pending or scheduled job |

Create request fields are `jobType` (required, 1–200 characters), `payload`
(required JSON object), optional `scheduledAt`, `maxAttempts` (default 5,
range 1–100), and `retryDelaySeconds` (default 0, range 0–86400). A future
`scheduledAt` creates a `SCHEDULED` job; an immediate or past schedule creates
a `PENDING` job. Cancellation is idempotent for already-cancelled jobs and
returns `409 Conflict` for other ineligible states. State changes use
optimistic locking.

List parameters include `status`, exact `jobType`, zero-based `page` (default
0), `size` (default 20, maximum 100), `sortBy` (`createdAt`, `updatedAt`,
`scheduledAt`, `jobType`, or `status`), and `direction` (`ASC` or `DESC`).
Errors use RFC-style Problem Details with a stable `code`; validation errors
also include field-level details.

Kafka publication/consumers, retries at runtime, request idempotency, workflow
definitions, authentication, Redis, monitoring, and the web console are future
work; persisted retry settings are not yet acted on by a worker.
