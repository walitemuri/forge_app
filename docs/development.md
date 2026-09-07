# Development guide

## Toolchain

| Area | Requirement |
| --- | --- |
| Controller | Java 21; Gradle wrapper included |
| Worker | CMake 3.20+, C++20 compiler, Protobuf, gRPC C++ |
| Database | PostgreSQL 17 via Docker Compose or an equivalent local instance |
| Smoke tests | Python 3 standard library; some failure-injection tests also require Linux process and network utilities |

The C++ worker is POSIX-specific. Develop it on Linux, macOS, or WSL with the required packages installed. The Java controller can run anywhere Java and PostgreSQL are available.

## Local environment

Start PostgreSQL from the repository root:

```bash
docker compose up -d
docker compose ps
```

The checked-in development configuration uses:

```text
database: forge
username: forge
password: forge
host port: 5433
```

These credentials are for an isolated local environment only.

## Controller workflow

```bash
cd controller
./gradlew clean test
./gradlew bootRun
```

Useful focused commands:

```bash
./gradlew test --tests 'dev.forge.controller.task.TaskServiceTest'
./gradlew test --tests 'dev.forge.controller.grpc.WorkerRegistrationConcurrencyTest'
./gradlew test --tests 'dev.forge.controller.grpc.WorkerCommandStreamConcurrencyTest'
```

The Spring context test connects to PostgreSQL and applies Flyway migrations. A connection failure at `127.0.0.1:5433` usually means Compose is not running, not that compilation failed.

## Worker workflow

Configure and build from the repository root:

```bash
cmake -S worker -B worker/build
cmake --build worker/build --parallel
./worker/build/forge-worker --controller=localhost:50051
```

Reconfigure after changing `CMakeLists.txt`, the shared proto, compiler settings, or installed package locations. Generated C++ protobuf sources are written below `worker/build/generated`.

The worker accepts one optional argument:

```text
--controller=<host:port>    default: localhost:50051
```

Set `FORGE_OUTBOX_DIR` to isolate test data or place the durable outbox on a different filesystem:

```bash
FORGE_OUTBOX_DIR=/tmp/forge-outbox \
  ./worker/build/forge-worker --controller=localhost:50051
```

## End-to-end smoke tests

With PostgreSQL, the controller, and at least one worker running:

```bash
python3 scripts/dag_smoke_test.py
python3 scripts/timeline_smoke_test.py
```

These exercise the public API, task execution, dependencies, cancellation, retries, timeouts, worker reporting, and timelines.

The remaining scripts probe restart and failure boundaries:

- `restart_smoke_test.py`, `recovery_timeline_smoke_test.py`, and `worker_reconnect_smoke_test.py` guide a manual controller restart.
- `outbox_*` scripts verify persistence barriers, ordering, disconnect replay, and worker-crash replay.
- `worker_*` scripts verify heartbeat reconnect, session loss, fencing, takeover atomicity, cold-start authority, and retired-session persistence.

> [!WARNING]
> Failure-injection scripts can stop or kill local worker processes, manipulate networking, hold database locks, and write temporary state below `/tmp`. Read the script before running it and use an isolated development environment with exactly the worker topology it expects.

## Changing the protocol

1. Edit only [`proto/forge.proto`](../proto/forge.proto).
2. Preserve existing field numbers; never reuse a removed tag.
3. Build both runtimes so Java and C++ bindings regenerate.
4. Update controller stream handling and worker message handling together.
5. Add a test for reconnect/replay behavior when the message participates in the durable stream.
6. Update [`docs/api.md`](api.md) if the external contract changes.

The controller Gradle protobuf plugin reads `../proto`. CMake uses the same file and places generated sources inside the build directory.

## Changing persistence

- Add a new, monotonically numbered Flyway migration under `controller/src/main/resources/db/migration`.
- Never edit an already-applied migration to change a deployed schema.
- Keep JPA mappings and database constraints aligned; Hibernate uses `ddl-auto=validate`.
- Prefer database constraints for invariants that must survive controller concurrency or restart.
- Test both a new database and an upgraded database when a migration transforms existing rows.

## Adding orchestration behavior

Forge separates logical task state from attempt state. When adding a transition:

1. define which logical and physical states are valid before and after it;
2. persist ownership or intent before external I/O;
3. emit the matching execution event;
4. make repeated or replayed worker events safe;
5. consider controller restart between every durable write and network send;
6. cover workflow cancellation, dependency release, automatic retry, and stale worker sessions.

Scheduled coordinators operate concurrently with REST requests and gRPC callbacks. Re-read current state inside the mutation boundary and preserve deterministic ordering where possible.

## Code and repository conventions

- Do not edit generated protobuf sources or checked build outputs.
- Keep Java packages under `dev.forge.controller` and public wire classes under `dev.forge.proto`.
- Keep worker code C++20-compatible and preserve process-group cleanup for cancellation.
- Treat `workerId` as stable host identity and `sessionId` as one process incarnation.
- Update diagrams and lifecycle tables when a state, edge, RPC, or recovery window changes.
- Avoid logging task secrets or full command payloads in new diagnostic paths.

## Verification checklist

Before opening a pull request:

```bash
docker compose up -d
(cd controller && ./gradlew test)
cmake -S worker -B worker/build
cmake --build worker/build --parallel
python3 scripts/dag_smoke_test.py
python3 scripts/timeline_smoke_test.py
```

Run the relevant failure-injection script for changes to outbox durability, session authority, reconnect behavior, worker loss, or recovery. Then review `git status` and confirm that build directories, logs, outbox files, Python caches, and temporary state are not included.
