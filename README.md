# FlowForge

FlowForge is a distributed workflow orchestration platform for defining,
submitting, scheduling, and observing durable background jobs. The initial
foundation uses Java 21 and Spring Boot for the backend, with Kafka for
asynchronous work distribution, PostgreSQL as the durable system of record,
Redis for ephemeral coordination, and a React/TypeScript operator UI.

## Architecture at a glance

The first delivery is a Spring Boot control-plane service with a health
endpoint. The target architecture separates the control plane (job and
workflow APIs), scheduler/dispatcher, worker runtime, and web console. Job
state is persisted in PostgreSQL; a transactional outbox publishes state
changes and dispatch messages to Kafka. Workers claim partitioned work,
execute with bounded leases, and report results through the control plane.
Redis is an optimization for short-lived coordination and caching, not the
source of truth.

See [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) for the
architecture, service boundaries, data model, API contracts, and delivery
roadmap. Milestone status is tracked in [docs/PROGRESS.md](docs/PROGRESS.md).

## Repository layout

```text
apps/
  web/                         React + TypeScript operator console (planned)
services/
  control-plane/               Spring Boot HTTP API (initial service)
  scheduler/                   Durable scheduling and Kafka dispatch (planned)
  worker/                      Worker runtime and execution adapters (planned)
libs/
  contracts/                   Shared API/event schemas (planned)
  java-common/                 Shared Java primitives (planned)
infra/
  docker/                      Local and deployment infrastructure (planned)
  monitoring/                  Prometheus/Grafana configuration (planned)
docs/
  IMPLEMENTATION_PLAN.md
  PROGRESS.md
```

## Prerequisites

- JDK 21
- Apache Maven 3.9 or newer

The initial control-plane health endpoint does not require PostgreSQL, Kafka,
or Redis to be running. Those dependencies will be introduced with the
milestones that integrate them.

## Build, test, and run

From the repository root:

```bash
mvn -B -ntp verify
mvn -pl services/control-plane spring-boot:run
```

Then check the service:

```bash
curl http://localhost:8080/actuator/health
```

The initial service binds to port `8080` and returns a Spring Boot Actuator
health response. See the implementation plan for the planned local dependency
stack and subsequent service setup.
