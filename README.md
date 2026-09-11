<div align="center">

# Forge

### Durable distributed task orchestration across Java and C++

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](controller/build.gradle)
[![C++20](https://img.shields.io/badge/C%2B%2B-20-00599C?logo=cplusplus&logoColor=white)](worker/CMakeLists.txt)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)](controller/build.gradle)
[![gRPC](https://img.shields.io/badge/gRPC-Protobuf-244C5A?logo=google&logoColor=white)](proto/forge.proto)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](docker-compose.yml)

Forge is a from-scratch execution engine for running shell commands on remote workers. It combines a Spring Boot control plane, a multithreaded C++ worker, a bidirectional gRPC command stream, PostgreSQL-backed state, DAG workflows, and failure-aware recovery.

[Quick start](#quick-start) · [Architecture](docs/architecture.md) · [API](docs/api.md) · [Development](docs/development.md) · [Operations](docs/operations.md)

</div>

> [!IMPORTANT]
> The Forge engine executes arbitrary processes and its internal REST/gRPC APIs are trusted-network interfaces. The [public demo configuration](docs/deployment.md) exposes only the dashboard and ten fixed, bounded workflow templates behind HTTPS. See [Security](SECURITY.md).

## Why this project is interesting

Forge focuses on the parts of distributed execution that become difficult after the happy path:

- **Durable worker events:** task-start and task-result messages are persisted to a filesystem outbox before transmission, replayed after reconnects, and removed only after controller acknowledgement.
- **Worker session fencing:** a stable worker ID is paired with a per-process session ID so stale incarnations cannot report results or reclaim authority.
- **Atomic takeover:** PostgreSQL stores the authoritative worker session, retired sessions, and recovery grace periods so controller restarts do not erase ownership decisions.
- **DAG workflows:** dependency validation rejects missing nodes, self-dependencies, and cycles; downstream tasks are released or skipped as prerequisites resolve.
- **Failure-aware execution:** bounded exponential retry, worker-loss recovery, cancellation, timeouts, ordered attempts, and persisted execution timelines are built into the model.
- **Capacity-aware scheduling:** the controller reserves worker capacity atomically and selects by effective load, CPU usage, and a deterministic worker-ID tie-breaker.

## System at a glance

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/system-overview-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="docs/diagrams/system-overview-light.svg">
  <img alt="Forge distributed workflow execution architecture" src="docs/diagrams/system-overview-light.svg" width="100%">
</picture>

The REST API is the user-facing control surface. The controller persists logical tasks and physical attempts, schedules work onto connected workers, and reconciles failure. Workers execute commands in dedicated process groups and use the outbox to bridge network or controller outages. The shared [Protocol Buffers contract](proto/forge.proto) keeps both runtimes aligned.

## Quick start

Docker with Compose is enough to run the complete demo:

```bash
docker compose up -d --build
```

Open [Workflow Templates](http://localhost:3000/templates) to launch any of ten
workflows. Start with parallel processing, retry recovery, or distributed video
processing. Click DAG nodes to inspect attempts and worker assignments. Templates
that intentionally fail explain their expected results before launch.

The images include the Java/C++ build tools, verification source, and a compact
30-second Tears of Steel footage excerpt. No full film download, host compiler, or source bind mount
is required. PostgreSQL, worker outboxes, and video artifacts use persistent
Docker volumes.

For an interactive recruiter-facing deployment, follow the [public deployment
guide](docs/deployment.md), which adds HTTPS, private backend ports, launch budgets,
and resource limits. For native development, see [Development](docs/development.md).

## Core model

| Concept | Role |
| --- | --- |
| **Workflow** | A validated DAG of named tasks. Successful nodes are preserved when failed workflows are manually retried. |
| **Task** | The logical unit requested by a client, including command, arguments, timeout, dependencies, and retry policy. |
| **Attempt** | One physical execution of a task, bound to a worker ID and worker session. |
| **Worker** | A C++ process that registers capabilities, receives commands, reports load, and runs up to four executor threads. |
| **Session** | A unique identity for one worker process incarnation, used to reject stale heartbeats, streams, and results. |
| **Execution event** | An append-only timeline record associated with a workflow, task, attempt, or worker. |

## Repository map

```text
forge_app/
├── controller/                 Spring Boot REST and gRPC control plane
│   └── src/main/
│       ├── java/dev/forge/     API, scheduling, recovery, and persistence
│       └── resources/db/       Versioned Flyway migrations
├── worker/                     C++20 worker and POSIX process executor
├── proto/forge.proto           Shared wire contract
├── scripts/                    End-to-end and failure-injection smoke tests
├── docs/                       Architecture, API, development, and operations
└── docker-compose.yml          Complete local Docker stack
```

## Documentation

- [Public demo deployment](docs/deployment.md) — Docker setup, HTTPS, template outcomes, and operating limits
- [Architecture](docs/architecture.md) — components, execution flow, state machine, recovery design, and data model
- [API reference](docs/api.md) — REST resources, payloads, lifecycle values, and gRPC contract
- [Development guide](docs/development.md) — prerequisites, builds, tests, migrations, protocol changes, and repository conventions
- [Operations guide](docs/operations.md) — configuration, persistence, failure behavior, observability, and troubleshooting
- [Contributing](CONTRIBUTING.md) — a focused change workflow and review checklist
- [Security](SECURITY.md) — trust assumptions and production-hardening gaps

## Current scope

Forge deliberately demonstrates orchestration and recovery mechanics rather than presenting itself as a finished hosted platform. The current controller is a single instance, worker transport uses insecure gRPC, the REST API is unauthenticated, and worker capacity is local to controller memory. These boundaries are documented so future work can be evaluated against a clear baseline.
