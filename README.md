# Job Scheduler

Submit Python scripts, run them once or on a cron schedule, trigger them manually, and see the status and
logs of every run. One Spring Boot application deployed in three roles (API, Scheduler, Worker).

**Designed before it was built:** [High-Level Design](docs/HLD.pdf) (requirements, capacity estimates,
architecture, trade-offs) and [Low-Level Design](docs/LLD.pdf) (schema, domain model, state machine,
scheduler and worker internals). Where the implementation deviates, see
[Changes from the design documents](#changes-from-the-design-documents).

**Stack:** Java 17, Spring Boot 4.1, PostgreSQL 17, RabbitMQ 4.4 (quorum queues), S3-compatible storage
(RustFS locally), Docker (one sandboxed container per run), Nginx, Mailpit.

```
User ──HTTPS+JWT──> Nginx ──> API x2 ──┬──> PostgreSQL  (users, jobs, runs; source of truth)
                                       ├──> S3 storage  (scripts/{jobId}/v{n}.py, logs/{jobId}/{runId}.log)
                                       └──> RabbitMQ    (manual runs, fast path)
                     Scheduler x2 ──> PostgreSQL (create runs) ──outbox──> RabbitMQ (delayed delivery)
                     Worker x N   <── RabbitMQ ──> Docker: one read-only, non-root container per run
```

## Run it

Needs JDK 17+ and Docker with Compose. The workers mount the Docker socket to start job containers, so
run Compose where the Docker Engine lives (Linux, macOS, or inside WSL on Windows).

```bash
./mvnw -DskipTests package            # Windows: .\mvnw.cmd -DskipTests package
docker compose up -d --build          # dev defaults; override in .env (see deploy/env.example)
docker compose ps
```

| What | Where |
|---|---|
| API (through Nginx) | http://localhost:8080/api/v1 |
| RabbitMQ console | http://localhost:15672 (jobscheduler / jobscheduler-dev) |
| Storage console | http://localhost:9001 |
| Mailpit (delay alerts) | http://localhost:8125 |
| PostgreSQL | localhost:5437, db/user `jobscheduler` |

End-to-end check of the running stack (51 checks: auth, validation, ownership, 409s, timeouts, OOM,
sandbox isolation, versioning, load balancing, a real scheduled run starting on time):

```bash
node scripts/e2e-smoke.mjs
```

## API

Base path `/api/v1`. Errors are RFC 7807 `ProblemDetail`. Another user's job or run is `404`, never `403`.

| Need | Method and path |
|---|---|
| Register / log in (returns JWT, 1 h) | `POST /auth/register`, `POST /auth/login` |
| Create, list, view a job | `POST /jobs`, `GET /jobs?page=&size=`, `GET /jobs/{id}` |
| Update (full replace; new script version) | `PUT /jobs/{id}` |
| Enable / disable | `PATCH /jobs/{id}` `{"enabled": false}` |
| Delete (soft) | `DELETE /jobs/{id}` |
| Manual trigger | `POST /jobs/{id}/runs` (409 if a run is RUNNING or a manual run is QUEUED) |
| Run history, one run, its logs | `GET /jobs/{id}/runs`, `GET /runs/{runId}`, `GET /runs/{runId}/logs` |

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"correct-horse-battery"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
curl -s localhost:8080/api/v1/jobs -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "name": "hello", "script": "print(\"hi\")", "timeoutSeconds": 30,
  "schedule": {"type": "CRON", "cron": "*/5 * * * *", "timezone": "Asia/Kolkata"}}'
```

## Code map

Package-by-module; `api`, `scheduler`, `worker`, `notification` import only `common` (enforced by ArchUnit).

| Package | Contents |
|---|---|
| `common.domain` | `Job` (aggregate root, all scheduling rules), `Schedule` (value object), `Run` (state machine), enums, `UuidV7` |
| `common.repository` | Spring Data repositories; locking queries (`FOR UPDATE [SKIP LOCKED]`) |
| `common.messaging` | Quorum-queue topology, `RunPublisher` + RabbitMQ implementation (publisher confirms) |
| `common.storage` | `ScriptStorage`, `LogStorage`, S3 implementation |
| `api` | Controllers, services, DTOs, JWT security, exception mapping |
| `scheduler` | `SchedulerTicker`, `RunCreationService`, `RunDispatcher` (outbox relay), `StuckRunSweeper` |
| `worker` | `RunMessageListener`, `RunExecutionService` (start / execute / finish), `DockerContainerRunner`, `LanguageRuntime` strategy |
| `notification` | `EmailNotificationService` (delay alerts) |
| `db/migration` | Flyway: schema, daily partitions of `runs`, partition maintenance function |

## Tests

```bash
./mvnw test       # 40 unit + architecture tests, no infrastructure
./mvnw verify     # + 14 integration tests against real PostgreSQL (Testcontainers)
```

`verify` starts PostgreSQL with Testcontainers, which needs a Docker daemon the JVM can reach. If it can't
(e.g. Maven on Windows, Docker inside WSL), point the tests at the Compose database instead: create a
database `jobscheduler_it` there and set `IT_DB_URL=jdbc:postgresql://localhost:5437/jobscheduler_it` and
`IT_DB_PASSWORD=jobscheduler-dev`. The
integration tests prove the concurrency claims with real threads and transactions: four schedulers on the
same window create each run exactly once; ten simultaneous manual triggers create one run and nine 409s;
the same message delivered to three workers starts the script once; two dispatchers publish each run once;
a broker outage leaves runs undispatched and loses nothing.

## Changes from the design documents

Found while implementing; this README takes precedence where they differ.

| Topic | Design said | Implemented | Why |
|---|---|---|---|
| Delayed delivery | rabbitmq-delayed-message-exchange plugin | RabbitMQ 4.4 quorum queue, `x-opt-delivery-time` header | The plugin is no longer maintained (built on Mnesia, removed in RabbitMQ 4.3). Quorum queues are replicated, so the HLD's "delayed messages live on one node" limitation disappears. |
| Minute window | Tick at 10:02 processes 10:02–10:03 | Tick at 10:02 creates the runs for 10:03–10:04 (look-ahead) | Cron runs fall on second 0, exactly when the tick fires, so the original window made every run late by the tick's own processing time (2.2 s measured). Now RabbitMQ releases each run at its exact time. |
| Late vs missed | Due before the window = system down | Late by less than `late-grace` (3 min) runs normally; older = `SYSTEM_DOWN` | A job whose row was locked (manual trigger, worker start, edit) at tick time is skipped by `SKIP LOCKED`; the original rule would fail its run as "system down". |
| Waiting runs of a disabled/deleted job | Not covered | Cancelled as `FAILED (JOB_DISABLED)`; worker re-checks at start | Otherwise a run already in the delayed queue still executes after disable. |
| `QUEUED` runs whose message is lost | Not covered (sweeper only handled `RUNNING`) | Sweeper fails them after 30 min (`EXPIRED_IN_QUEUE`) | A lost manual run would block manual triggers of its job forever (the 409 rule). |
| Object storage | MinIO | RustFS server, MinIO Java SDK as the S3 client | MinIO no longer publishes container images. The code only speaks S3. |
| Timeout enforcement | Worker kills the container at the timeout | Watchdog armed before `start`, plus `timeout -s KILL` inside the container (+5 s) | Observed once: a slow Docker API call delayed the worker thread, so the run outlived its timeout. The kill no longer depends on where that thread is. |
| Script into the container | Fetch to a temp folder (implied bind mount) | `docker cp` into a per-run anonymous volume at `/job` | The worker runs in a container, so its temp folder is not a path on the Docker host; and copying into a read-only root filesystem is refused. |
| Job name uniqueness | Unique `(owner_id, name)` | Unique among live jobs (`WHERE deleted_at IS NULL`) | A soft-deleted job would otherwise block its name forever. |
| PUT and script versions | Optimistic locking for edits | PUT locks the job row while uploading the new version | Two concurrent PUTs could both write `v{n+1}`; the lock keeps committed versions immutable. The one deliberate network call inside a transaction (rare, one row). |
| Max timeout | Not specified | 1 hour; queue consumer timeout 90 min | The worker acks only after the run; RabbitMQ must not reclaim the message mid-run. |
| Manual trigger of a disabled job | Not specified | Allowed | Disable stops the schedule; running on demand is still useful. |
| Auth filter | Custom `JwtAuthFilter` | Spring Security's bearer-token filter with a Nimbus HS256 decoder | Same behaviour, less code to get wrong. |

Two bugs found by the end-to-end run and fixed: the API's fast-path "dispatched" marker could be skipped
when a worker started the run within milliseconds of the publish; and Nginx cached the API containers' IPs
at startup, so a recreated API instance dropped out of rotation (now re-resolved through Docker DNS).

## Known limitations / next steps

- **Load test** with measured numbers (HLD build scope) is not done yet.
- **Worker shutdown:** a worker stopped mid-run leaves the run `RUNNING` until the sweeper fails it
  (`WORKER_CRASHED`); its container is removed when that worker starts again.
- **Egress:** job containers get their own bridge network (no route to Postgres, RabbitMQ or storage), but
  private IP ranges and the metadata address are not firewalled.
- **TLS** is not terminated at Nginx locally. **Kubernetes/KEDA** (HLD build phase 2) is not started.
- Runs are looked up by id across all daily partitions (no pruning); fine at 30 partitions.
