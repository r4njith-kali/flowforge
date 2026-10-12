# Implementation Progress

Last updated: 2026-10-12

| Milestone | Status | Evidence / next acceptance checks |
| --- | --- | --- |
| Foundation and architecture | Complete | Architecture, roadmap, API/data model, lifecycle/recovery guidance, Maven reactor, and README setup are in place. `mvn -B -ntp verify` passed (1 test); live `GET /actuator/health` returned `{"status":"UP"}`. Compiled with `--release 21` and tested on installed Java 25 because JDK 21 is not installed in the environment. |
| Service skeletons and local runtime | Not started | Add Docker Compose dependencies and service container; verify all dependencies start locally. |
| Durable control-plane model | Not started | Add migrations, API contract, job persistence, and PostgreSQL integration tests. |
| Idempotent submission and outbox | Not started | Verify duplicate submission, conflict on request-hash mismatch, and restart-safe publication. |
| Scheduling and Kafka dispatch | Not started | Verify due-work claiming, backpressure, retry scheduling, and dead-letter behavior. |
| Worker runtime and recovery | Not started | Verify leases, heartbeats, cancellation, retries, and worker-loss recovery. |
| Operator UI | Not started | Verify typed API integration, core workflows, accessibility, and UI tests. |
| Production readiness | Not started | Verify security, load/resilience, dashboards/alerts, packaging, and runbooks. |

## Current increment

- Repository baseline: empty project scaffold with README and `.gitignore`.
- Delivered: documented architecture and roadmap plus a Java 21 Spring Boot
  control-plane starter exposing `/actuator/health`.
- Not included: job/workflow APIs, persistence, Kafka, Redis, UI, Compose, or
  production deployment.
