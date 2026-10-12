# End-to-end runtime demonstration

This document records a real local verification of the control plane, outbox
publisher, Kafka broker, and two independently running worker containers. The
run took place on 2026-10-13 local time; PostgreSQL and worker log timestamps
below are UTC unless marked `+08:00`. Job IDs are from that run, not sample
fixtures.

## Reproduce locally

Prerequisites: Docker Engine/Compose, Maven 3.9+, `jq`, and a Java runtime
supported by the project. Start with a disposable local database if you do not
want to retain demonstration records.

1. Create an ignored local environment file and set a private password. The
   password values for Compose and the host-run control plane must match:

   ```bash
   cp .env.example .env
   # Edit POSTGRES_PASSWORD and DB_PASSWORD; never commit .env.
   ```

   If ports 5432 or 9092 are already occupied, select free ports and update
   all corresponding variables. For example:

   ```dotenv
   POSTGRES_PORT=15432
   DB_URL=jdbc:postgresql://localhost:15432/flowforge
   KAFKA_PORT=19092
   KAFKA_BOOTSTRAP_SERVERS=localhost:19092
   ```

   Compose uses `KAFKA_PORT` both for host port publication and for the
   broker's advertised host listener. Container workers always use
   `kafka:29092`.

2. Start PostgreSQL and Kafka:

   ```bash
   docker compose --env-file .env -f infra/docker/compose.yaml up -d postgres kafka
   docker compose --env-file .env -f infra/docker/compose.yaml ps
   ```

3. Start the control plane in a terminal. Flyway runs on startup and the
   control plane owns all shared-schema migrations:

   ```bash
   set -a
   . ./.env
   set +a
   mvn -pl services/control-plane spring-boot:run
   ```

   Wait for `http://localhost:8080/actuator/health` to report `UP`, then
   confirm V1-V4 succeeded:

   ```bash
   curl --fail --silent http://localhost:8080/actuator/health
   docker compose --env-file .env -f infra/docker/compose.yaml exec -T postgres \
     sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" \
       -d "$POSTGRES_DB" -c \
       "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"'
   ```

4. In another terminal, build and start two workers:

   ```bash
   docker compose --env-file .env -f infra/docker/compose.yaml \
     up --build -d --scale worker=2 worker
   docker compose --env-file .env -f infra/docker/compose.yaml ps
   docker compose --env-file .env -f infra/docker/compose.yaml logs --tail=30 worker
   ```

   Check that two worker containers are `Up` and each logs
   `Started WorkerApplication`. Workers rely on the control plane to have
   applied the current Flyway schema.

5. Submit an echo job, repeat the exact request and key, then poll the
   returned resource:

   ```bash
   body='{"jobType":"demo.echo","payload":{"message":"hello-e2e"},"maxAttempts":3}'
   curl --silent --show-error -o /tmp/flowforge-first.json -w 'HTTP %{http_code}\n' \
     -X POST http://localhost:8080/api/v1/jobs \
     -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: e2e-echo-unique-value' -d "$body"
   curl --silent --show-error -o /tmp/flowforge-replay.json -w 'HTTP %{http_code}\n' \
     -X POST http://localhost:8080/api/v1/jobs \
     -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: e2e-echo-unique-value' -d "$body"
   job_id=$(jq -r .id /tmp/flowforge-first.json)
   test "$job_id" = "$(jq -r .id /tmp/flowforge-replay.json)"
   echo "same job ID for initial request and replay: $job_id"
   curl --fail --silent "http://localhost:8080/api/v1/jobs/$job_id"
   ```

   Both create requests should return `201 Created` and the same job ID.
   Poll the detail route until the job reaches `COMPLETED`. The API response
   does not expose handler output; verify `jobs.result` and the attempt row
   directly in PostgreSQL:

   ```sql
   SELECT id, status, attempt_count, result, started_at, completed_at
   FROM jobs WHERE id = '<value of job_id>';
   SELECT attempt_number, status, worker_id, fencing_token, result
   FROM job_attempts WHERE job_id = '<value of job_id>';
   ```

6. Verify that a future job stays scheduled before its due time. Choose a UTC
   instant about one minute in the future:

   ```bash
   # macOS:
   scheduled_at=$(date -u -v+60S '+%Y-%m-%dT%H:%M:%SZ')
   curl -i -X POST http://localhost:8080/api/v1/jobs \
     -H 'Content-Type: application/json' \
     -d "{\"jobType\":\"demo.delay\",\"payload\":{\"delayMillis\":1000},\"scheduledAt\":\"$scheduled_at\",\"maxAttempts\":3}"
   ```

   Before `scheduled_at`, detail should report `SCHEDULED`, with no
   `startedAt`, `attempt_count = 0`, and no `job_attempts` rows. After the
   due time, poll until `COMPLETED`.

7. Verify cancellation by creating a job scheduled well into the future, then
   immediately calling `POST /api/v1/jobs/{id}/cancel`. Expect `200 OK` and
   `CANCELLED`; query PostgreSQL to confirm no attempts were inserted and the
   unpublished outbox event has `cancelled_at`.

8. Restart both workers while no demonstration job is running, confirm both
   log a new `Started WorkerApplication` line, and submit another echo job.
   Poll it to `COMPLETED`. This is a safe restart smoke test, not a forced
   mid-execution crash test. The PostgreSQL lease-expiry and stale-fencing
   recovery paths are covered by the worker Testcontainers suite.

For the full automated suite, run:

```bash
mvn -B -ntp clean verify
```

The Docker host used for the recorded run required the local-only Testcontainers
compatibility override:

```bash
mvn -B -ntp clean verify -Dtestcontainers.docker-api-version=1.40
```

The override is not configured in Compose or CI and should only be used where
the local Docker API negotiation requires it.

## Observed verification evidence

### Environment and schema

- Docker Compose reported PostgreSQL 16 and Kafka 3.9.1 healthy. Host ports
  5432 was already occupied by a local PostgreSQL listener, so this run used
  15432 for PostgreSQL and 19092 for Kafka.
- The health endpoint returned
  `{"status":"UP","groups":["liveness","readiness"]}`.
- The Flyway history query returned `1|true`, `2|true`, `3|true`, and
  `4|true`.
- Two separate Compose containers, `docker-worker-1` and `docker-worker-2`,
  each logged `Started WorkerApplication`. Jobs below were executed by
  distinct persisted worker IDs.

### Echo execution and idempotency replay

For job `4b1d98a0-21ba-40bd-a009-5c1a66a4a38e`, the initial request and
identical replay both returned HTTP 201 with this same ID. The first response
was initially `PENDING`; the detail route later returned `COMPLETED` with
`attemptCount: 1`.

The database contained:

```text
jobs:
COMPLETED | attempt_count=1 | result={"echo":{"message":"milestone-5-e2e-confirmed"}}
started_at=2026-10-12 17:48:06.210754+00
completed_at=2026-10-12 17:48:06.273483+00

job_attempts:
attempt=1 | COMPLETED | worker-30041399-b655-45e6-9aca-8ef67200a512
fencing_token=1
result={"echo":{"message":"milestone-5-e2e-confirmed"}}

outbox_events:
JobCreated | published_at=2026-10-12 17:48:06.141215+00
```

The database count checks returned one idempotency record, one job, one outbox
event, and one attempt for this key/job; replay did not add duplicates.

The worker log recorded `Job execution completed` at
`2026-10-12T17:48:06.278Z`; the control-plane log recorded `Published outbox
event` at `2026-10-13T01:48:06.150+08:00`.

A separate five-second `demo.delay` job,
`61884247-2bae-45f7-8525-1e5c5efc2f54`, was polled through the observed API
states `PENDING`, `DISPATCHED`, `RUNNING`, and `COMPLETED`. PostgreSQL stored
one `COMPLETED` attempt with result `{"delayedMillis":5000}`.

### Scheduling and cancellation

Scheduled job `fa8b0624-b2df-4109-b10d-32242763a2fd` was set for
`2026-10-12T17:49:21Z`. Before that instant the API returned `SCHEDULED`,
`attemptCount: 0`, and null `startedAt`/`completedAt`; PostgreSQL reported
`SCHEDULED|0|0` for status, job attempt count, and number of attempt rows.
It later started at `2026-10-12 17:49:21.532498+00` and completed at
`17:49:22.582119+00`, with one persisted attempt and
`{"delayedMillis":1000}` as the attempt result.

Job `90d609ca-2b3d-4a1b-83de-1dd8d4b28e4f`, scheduled for
`2026-10-12T17:54:54Z`, was cancelled at `17:54:34.980579Z`, before it was
due. After the scheduled instant had passed, the API still returned
`CANCELLED`, `attemptCount: 0`, null `startedAt`, and no `completedAt` change.
PostgreSQL confirmed
`CANCELLED|0|0|2026-10-12 17:54:34.980579+00`: zero job attempts, zero
attempt records, and a cancelled outbox event.

### Restart and automated tests

Both workers were restarted and logged startup again at approximately
`2026-10-12T17:50:18Z`. A subsequent echo job,
`cbe0deaf-4d39-4a33-9ba1-1d1c88b9bb90`, reached `COMPLETED` with one attempt
and persisted result `{"echo":{"message":"post-restart"}}`.

After the fixes below, the full
`mvn -B -ntp clean verify -Dtestcontainers.docker-api-version=1.40` run
reported **BUILD SUCCESS: 50 tests passed, 0 failures, 0 errors** (41
control-plane and 9 worker tests). The worker Docker image also built
successfully, and `docker compose ... config --quiet` passed. Maven 3.9.11
used OpenJDK 25.0.1 with Java 21 release compilation; the local Maven test run
was not performed on a JDK 21 host runtime.

### Issues found and corrected during this run

The first worker image build exposed that its JAR was not repackaged as an
executable Spring Boot archive. Both containers restarted with
`no main manifest attribute`. The worker Maven configuration now binds the
Spring Boot `repackage` goal; rebuilding the image produced a runnable JAR
and both replicas started.

Because 9092 was already occupied, the first run used host port 19092. The
Compose broker initially still advertised `localhost:9092` to host clients;
the control-plane producer therefore could not connect, and its first
demonstration job
(`544ba51b-b337-421e-89b4-ab95fded3168`) ultimately failed after the bounded
outbox publication retries. The Compose advertised host listener now follows
`KAFKA_PORT`. After recreating Kafka and restarting the control plane and
workers, the later echo, scheduled, cancellation, and restart checks above
passed. The initial failed job is retained in the local test database as
evidence of the failure; it was not counted as a successful execution.

The restart check exercised idle worker restart and subsequent processing.
An in-flight forced crash/lease-expiry scenario was not induced against this
shared local runtime; that behavior was verified by the worker's
PostgreSQL/Kafka Testcontainers tests instead.
