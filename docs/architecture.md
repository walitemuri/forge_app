# Architecture

This document describes the architecture implemented in the repository today. Forge has two independently deployed runtimes—a Java controller and one or more C++ workers—plus PostgreSQL and a worker-local durable outbox.

## Design goals

- Preserve task, attempt, workflow, ownership, and timeline state across controller restarts.
- Prevent stale worker processes from mutating current execution state.
- Deliver worker lifecycle events across transient disconnects without reordering them.
- Keep logical task state separate from physical execution attempts.
- Support dependency-aware workflows without allowing invalid or cyclic graphs.
- Make cancellations, worker loss, retry eligibility, and recovery observable.

## Component view

```mermaid
flowchart LR
    user[API consumer]

    subgraph controlPlane["Controller process - Java 21"]
        rest[Spring REST controllers]
        workflow[Workflow service]
        tasks[Task service]
        coordinators[Dependency, pending, retry, and recovery coordinators]
        scheduler[Worker scheduler]
        registry[In-memory worker registry]
        grpcServer[gRPC service]
        persistence[JPA repositories and Flyway]
    end

    postgres[(PostgreSQL 17)]

    subgraph workerProcess["Worker process - C++20"]
        connection[Registration, heartbeat, and stream loop]
        executors[Task executor pool]
        runner[POSIX process executor]
        outbox[(Filesystem outbox)]
    end

    user -->|HTTP JSON| rest
    rest --> workflow
    rest --> tasks
    workflow --> tasks
    tasks --> scheduler
    coordinators --> tasks
    scheduler --> registry
    grpcServer --> registry
    tasks --> persistence
    workflow --> persistence
    coordinators --> persistence
    persistence -->|JDBC| postgres
    grpcServer <-->|Bidirectional gRPC| connection
    connection --> executors
    executors --> runner
    executors --> outbox
    outbox --> connection
```

### Controller

The controller exposes REST resources on port `8080` and starts a separate gRPC server on port `50051`. Its main responsibilities are:

- validate and persist standalone tasks and workflow DAGs;
- create a new attempt for each physical execution;
- reserve capacity and dispatch work to the least-loaded eligible worker;
- consume idempotently identified worker events and acknowledge them;
- coordinate dependency release, retries, cancellations, health checks, and restart recovery;
- expose current state and append-only execution timelines.

PostgreSQL is the source of truth for workflows, tasks, attempts, worker authority, retired sessions, delayed recovery, and execution events. Live worker connections and metrics are intentionally held in memory.

### Worker

The worker maintains a stable `workerId` derived from its hostname and generates a unique `sessionId` for every process incarnation. It:

- registers capabilities and reports CPU, memory, and running-task metrics every five seconds;
- maintains a bidirectional command stream and automatically reconnects;
- executes commands in a bounded pool of `min(cpuCores, 4)` threads;
- gives each command its own POSIX process group so cancellation reaches descendants;
- captures stdout, stderr, exit code, timeout, and cancellation outcomes;
- persists task-accepted and task-result events locally before sending them.

The outbox defaults to `$HOME/.forge/outbox/<workerId>` and can be relocated with `FORGE_OUTBOX_DIR`.

## Successful execution flow

```mermaid
sequenceDiagram
    participant Client
    participant Controller
    participant Database as PostgreSQL
    participant Worker
    participant Outbox as Worker outbox

    Worker->>Controller: RegisterWorker with workerId and sessionId
    Controller->>Database: Claim or verify durable authority
    Controller-->>Worker: Registration accepted
    Worker->>Controller: Open ConnectWorker stream and send WorkerHello

    Client->>Controller: POST task or workflow
    Controller->>Database: Persist logical task as PENDING or BLOCKED
    Controller->>Database: Create attempt and persist ownership
    Controller-->>Worker: TaskAssignment

    Worker->>Worker: Start child process group
    Worker->>Outbox: Persist TaskAccepted event
    Outbox->>Controller: Replay TaskAccepted
    Controller->>Database: Mark attempt and task RUNNING
    Controller-->>Worker: WorkerEventAck
    Worker->>Outbox: Remove acknowledged event

    Worker->>Worker: Wait, cancel, or enforce timeout
    Worker->>Outbox: Persist TaskResult event
    Outbox->>Controller: Replay TaskResult
    Controller->>Database: Complete attempt, task, and timeline
    Controller-->>Worker: WorkerEventAck
    Worker->>Outbox: Remove acknowledged event

    Client->>Controller: GET task, workflow, attempts, or events
    Controller->>Database: Read persisted state
    Controller-->>Client: JSON response
```

### Ordering and acknowledgement

Worker events use deterministic IDs derived from the attempt: `<attemptId>:accepted` and `<attemptId>:result`. `ReliableEventSender` maintains insertion order, withholds events until the persistence barrier succeeds, replays every unacknowledged durable event when the stream changes, and deletes its `.event` file only after a controller ACK. The controller serializes ACK writes with outbound assignments and cancellation commands.

This is an at-least-once transport pattern. Correctness therefore depends on the controller validating the worker session and treating repeated event identities idempotently.

## Task lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING: no dependencies or all succeeded
    [*] --> BLOCKED: unresolved dependencies
    BLOCKED --> PENDING: every dependency succeeds
    BLOCKED --> SKIPPED: dependency cannot succeed
    BLOCKED --> CANCELLED: task or workflow cancelled
    PENDING --> DISPATCHED: worker capacity reserved
    PENDING --> CANCELLED: task or workflow cancelled
    DISPATCHED --> RUNNING: accepted event
    DISPATCHED --> FAILED: send or pre-start failure
    DISPATCHED --> LOST: worker session lost
    DISPATCHED --> CANCELLED: cancellation completes
    RUNNING --> SUCCEEDED: successful result
    RUNNING --> FAILED: unsuccessful result
    RUNNING --> LOST: worker session lost
    RUNNING --> CANCELLED: cancellation completes
    FAILED --> DISPATCHED: retry eligible and capacity available
    LOST --> DISPATCHED: retry eligible and capacity available
    SUCCEEDED --> [*]
    FAILED --> [*]: retry budget exhausted
    LOST --> [*]: retry budget exhausted
    CANCELLED --> [*]
    SKIPPED --> [*]
```

`maxAttempts` limits automatic physical executions to 1–10. A failed attempt becomes eligible after exponential backoff: 5, 10, 20, 40 seconds and so on, capped at 300 seconds. The retry coordinator checks once per second. Pending dispatch and dependency reconciliation each run every 500 milliseconds.

The workflow status is derived from its task states rather than stored independently. A workflow is successful only when every task succeeds. Exhausted failures take precedence and, together with skipped downstream nodes, produce `FAILED`; otherwise cancellation produces `CANCELLED`, while in-flight or retryable work produces `RUNNING`.

## Worker authority and recovery

The stable worker ID is not enough to identify an executing process. Two incarnations may overlap during a crash, network partition, or restart. Forge pairs it with a per-process session ID and keeps durable ownership state.

```mermaid
sequenceDiagram
    participant Old as Session A
    participant Controller
    participant Database as PostgreSQL
    participant New as Session B

    Old->>Controller: Heartbeats and command stream
    Controller->>Database: Authority is workerId to session A
    Controller->>Controller: Heartbeat exceeds 15 seconds
    Controller-->>Old: Disconnect stream and mark offline

    New->>Controller: Register same workerId with session B
    Controller->>Database: Begin takeover transaction
    Controller->>Database: Persist recovery grace for session A
    Controller->>Database: Retire session A permanently
    Controller->>Database: Compare-and-swap authority A to B
    Database-->>Controller: Commit atomically
    Controller-->>New: Registration accepted

    Old->>Controller: Late heartbeat, stream, or result
    Controller-->>Old: Reject stale retired session
    New->>Controller: Replay durable events if present
    Controller-->>New: ACK authoritative events
```

Important recovery windows:

- **Heartbeat timeout:** the controller checks every five seconds and marks a worker lost after 15 seconds without an accepted heartbeat.
- **Session replay grace:** after takeover, the previous session receives a durable 10-second window in which already-persisted events can be reconciled before its active attempts are marked lost.
- **Controller cold-start grace:** after controller restart, a persisted authoritative session gets 10 seconds to reconnect before a fresh session can take over.
- **Startup task recovery:** interrupted `DISPATCHED` and `RUNNING` state is reconciled before the gRPC server begins accepting connections, while durable recovery rows prevent premature loss during valid replay windows.

Retired sessions are persisted separately from short-lived recovery rows. This prevents a stale process from becoming authoritative again after its grace record has expired.

## Persistence model

```mermaid
erDiagram
    FORGE_WORKFLOWS ||--o{ FORGE_TASKS : contains
    FORGE_TASKS ||--o{ FORGE_TASK_ARGUMENTS : orders
    FORGE_TASKS ||--o{ TASK_ATTEMPTS : executes_as
    FORGE_TASKS ||--o{ TASK_DEPENDENCIES : dependent
    FORGE_TASKS ||--o{ TASK_DEPENDENCIES : prerequisite
    FORGE_WORKFLOWS o|--o{ EXECUTION_EVENTS : describes
    FORGE_TASKS o|--o{ EXECUTION_EVENTS : describes
    TASK_ATTEMPTS o|--o{ EXECUTION_EVENTS : describes

    FORGE_WORKFLOWS {
        string id PK
        string name
        timestamptz created_at
        boolean cancel_requested
    }
    FORGE_TASKS {
        string id PK
        string workflow_id FK
        string workflow_task_key
        string command
        string status
        int max_attempts
        int timeout_seconds
        boolean cancel_requested
    }
    TASK_ATTEMPTS {
        string id PK
        string task_id FK
        int attempt_number
        string worker_id
        string worker_session_id
        string status
        timestamptz started_at
        timestamptz finished_at
    }
    FORGE_TASK_ARGUMENTS {
        string task_id FK
        int argument_index
        text argument_value
    }
    TASK_DEPENDENCIES {
        string task_id PK, FK
        string depends_on_task_id PK, FK
    }
    EXECUTION_EVENTS {
        bigint id PK
        string event_type
        string workflow_id FK
        string task_id FK
        string attempt_id FK
        string worker_id
        timestamptz created_at
    }
    WORKER_AUTHORITIES {
        string worker_id PK
        string session_id
        timestamptz updated_at
    }
    WORKER_SESSION_RECOVERIES {
        string id PK
        string worker_id
        string session_id
        timestamptz recover_after
    }
    RETIRED_WORKER_SESSIONS {
        string id PK
        string worker_id
        string session_id
        timestamptz retired_at
    }
```

Worker authority tables have no foreign key to the in-memory worker registry. They preserve coordination facts even when no worker is connected. Schema evolution is append-only through `controller/src/main/resources/db/migration` and Hibernate runs in `validate` mode.

## Scheduling

Scheduling and capacity reservation occur inside one synchronized controller method. Eligible workers must be online, have an attached command stream, and have spare task capacity. Selection is ordered by:

1. effective load (`max(runningTasks, outstandingReservations)`);
2. reported CPU usage;
3. worker ID, for deterministic ties.

The selected worker is reserved before the scheduler lock is released. This prevents concurrent REST and coordinator activity from over-selecting the same worker. Capacity is process-local, which is one reason the current controller is intentionally single-instance.

## Consistency boundaries

- Database transactions protect workflow creation, workflow retry reset, session takeover, and durable recovery reconciliation.
- Task dispatch persists ownership before writing the gRPC assignment.
- Workflow cancellation persists workflow-level intent before sweeping child tasks, allowing restart recovery to finish an interrupted sweep.
- Worker results are accepted only for the current worker session and matching attempt ownership.
- Scheduled coordinators are defensive: they re-read current state and tolerate work changing between selection and mutation.

## Known architectural boundaries

- The REST and gRPC surfaces are unauthenticated; gRPC uses insecure channel credentials.
- Worker commands run with the worker process account and are not sandboxed.
- The controller is designed as a single process; worker registry and scheduling reservations are not distributed.
- There is no admission control, tenant isolation, artifact store, log streaming, metrics backend, or trace propagation.
- Outbox durability is local to a worker filesystem; losing that disk can lose unacknowledged events.
- API errors are plain text rather than a versioned structured error envelope.

These are explicit extension points, not hidden production claims. See [Operations](operations.md) and [Security](../SECURITY.md) for deployment implications.
