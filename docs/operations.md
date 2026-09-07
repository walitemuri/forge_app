# Operations guide

Forge is currently designed for a trusted single-controller development deployment. This guide describes its runtime behavior and makes the boundaries for a hardened deployment explicit.

## Services and ports

| Component | Default | Purpose |
| --- | --- | --- |
| REST API | `0.0.0.0:8080` | Submit and inspect tasks/workflows; cancel and retry work |
| gRPC server | `0.0.0.0:50051` | Worker registration, heartbeats, command stream, and ACKs |
| PostgreSQL | `127.0.0.1:5433` on the host | Durable control-plane state |

## Configuration

Spring Boot properties can be supplied through normal property files, command-line flags, or environment variables.

| Setting | Default | Environment form |
| --- | --- | --- |
| `spring.datasource.url` | `jdbc:postgresql://127.0.0.1:5433/forge` | `SPRING_DATASOURCE_URL` |
| `spring.datasource.username` | `forge` | `SPRING_DATASOURCE_USERNAME` |
| `spring.datasource.password` | `forge` | `SPRING_DATASOURCE_PASSWORD` |
| `forge.grpc.port` | `50051` | `FORGE_GRPC_PORT` |
| `server.port` | `8080` | `SERVER_PORT` |

Worker settings:

| Setting | Default | Notes |
| --- | --- | --- |
| `--controller=<host:port>` | `localhost:50051` | Controller gRPC address |
| `FORGE_OUTBOX_DIR` | `$HOME/.forge/outbox` | Root of worker event persistence |
| Executor threads | `min(cpuCores, 4)`, at least 1 | Reported to the controller as worker capacity |

## Startup and shutdown

Recommended order:

1. Start PostgreSQL and confirm port `5433` is reachable.
2. Start the controller. Flyway migrates the database; Hibernate validates it.
3. Wait for both REST and gRPC startup messages.
4. Start one or more workers with unique hostnames/worker IDs.
5. Confirm `GET /api/workers` shows `online=true` and `commandStreamConnected=true`.

On graceful controller shutdown, the gRPC server is asked to stop. Workers retry registration every two seconds until it returns. On worker shutdown or crash, unacknowledged durable events remain in its outbox for replay by a replacement process using the same worker ID and outbox path.

## Health and readiness checks

The project does not currently include Spring Actuator. Use these functional probes:

```bash
curl -fsS http://localhost:8080/api/workers
curl -fsS http://localhost:8080/api/tasks
```

A ready execution path requires all of the following:

- REST responds;
- the controller has a healthy PostgreSQL connection;
- at least one worker is online;
- its command stream is connected;
- it has capacity.

An empty worker list is a valid API response but means tasks remain `PENDING` and retries remain deferred.

## Timing behavior

| Mechanism | Interval / threshold |
| --- | --- |
| Worker heartbeat | every 5 seconds |
| Heartbeat RPC deadline | 2 seconds |
| Controller worker-health scan | every 5 seconds |
| Worker considered lost | more than 15 seconds since accepted heartbeat |
| Registration/stream reconnect delay | 2 seconds |
| Previous-session replay grace | 10 seconds |
| Controller cold-start reconnect grace | 10 seconds |
| Pending dispatch scan | 500 ms |
| Dependency scan | 500 ms |
| Retry eligibility scan | 1 second |
| Automatic retry backoff | 5 seconds doubled per failed attempt, capped at 300 seconds |

## Failure behavior

| Failure | Expected behavior |
| --- | --- |
| No worker capacity | Task remains `PENDING`; dispatch is retried by the coordinator |
| Assignment stream write fails | Reservation is released; attempt and task become `FAILED` |
| Worker event cannot be sent | Event remains in the durable outbox and replays on a new stream |
| Worker process crashes | Controller eventually marks its active attempts `LOST`; automatic retry may create a new attempt |
| Worker freezes or partitions | Heartbeat timeout disconnects its stream and schedules/reconciles session loss |
| Stale worker returns | Retired-session and authority checks reject its registration, heartbeat, stream, and events |
| Controller restarts | Persisted tasks, cancellation intent, recovery grace, authority, and retired sessions drive startup recovery |
| Task exceeds timeout | Worker kills the process group and reports an unsuccessful result |
| Task is cancelled | Worker sends `SIGTERM`, waits up to two seconds, then escalates to `SIGKILL` if required |
| Dependency exhausts retries | Downstream blocked tasks become `SKIPPED` |

## Persistence and backup

Back up both sides of the delivery protocol:

- **PostgreSQL:** workflows, tasks, attempts, timelines, dependency edges, cancellation intent, worker authority, retired sessions, and recovery deadlines.
- **Worker outbox filesystem:** accepted/result events that have crossed the worker durability barrier but have not yet been acknowledged.

The Compose volume is named `forge-postgres-data`. `docker compose down` keeps it; `docker compose down -v` deletes it and should be treated as destructive.

Do not manually remove a worker outbox while the worker is stopped unless losing unacknowledged execution results is acceptable. If moving a worker process to another machine while preserving its stable worker ID, move its outbox data with it and ensure only one incarnation starts.

## Observability

Today, observability consists of:

- structured persisted execution-event records exposed by workflow, task, and attempt endpoints;
- worker status and resource snapshots exposed by `/api/workers`;
- controller and worker console logs;
- task stdout/stderr persisted on the logical task and physical attempt.

Execution event IDs provide stable ordering inside PostgreSQL. Worker `lastHeartbeat` is epoch milliseconds from controller memory; execution timestamps are persisted UTC instants.

For a hardened deployment, add metrics for queue depth, dispatch latency, task duration, retry counts, lost workers, outbox depth, ACK latency, session-fencing rejections, and coordinator errors. Add correlation IDs and distributed tracing across REST, persistence, gRPC, and child execution.

## Troubleshooting

### Controller test or startup cannot connect to PostgreSQL

```bash
docker compose ps
docker compose logs postgres
```

Confirm the host is listening on `5433` and that datasource overrides match the Compose credentials.

### Tasks remain PENDING

Check `/api/workers`. A schedulable worker must be online, have a connected command stream, and report capacity greater than its effective load. Review controller logs for `No available Forge workers` or deferred dispatch messages.

### Worker repeatedly fails registration

Likely causes are an active duplicate session, persisted cold-start authority waiting for its reconnect grace, or a permanently retired session. Compare the worker/session IDs in logs and inspect `worker_authorities`, `retired_worker_sessions`, and `worker_session_recoveries` before changing data manually.

### Result is executed but not visible in the controller

Inspect the worker outbox and logs. A `.event` file indicates the result crossed the durability barrier but has not been acknowledged. Check gRPC connectivity and confirm the worker's session is still authoritative. Do not delete the event as a first response.

### Worker build cannot find Protobuf or gRPC

Install the C++ development packages, not only language-specific runtime packages. CMake requires a `protobuf::protoc` target and the gRPC package configuration with `gRPC::grpc++` and `gRPC::grpc_cpp_plugin`.

## Production-hardening checklist

Before any untrusted or multi-user deployment:

- authenticate and authorize every REST and gRPC action;
- use TLS or mTLS and bind services to intentional interfaces;
- replace development database credentials and use secret management;
- isolate worker execution with containers, namespaces, cgroups, seccomp, and a non-privileged identity;
- enforce command/image allowlists, quotas, payload limits, and output limits;
- make controller leadership and scheduling state safe for multiple replicas;
- move worker event durability to replicated storage or define disk-loss semantics;
- add health endpoints, metrics, tracing, structured logs, retention, and alerting;
- document upgrade compatibility for protobuf and database migrations;
- add API versioning and structured error responses.
