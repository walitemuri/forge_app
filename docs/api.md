# API reference

Forge exposes an HTTP/JSON API for users and a gRPC/Protocol Buffers API for workers.

## Conventions

- REST base URL: `http://localhost:8080`
- Content type: `application/json`
- Identifiers: server-generated UUID strings
- Timestamps: ISO-8601 UTC values in JSON
- Asynchronous mutations generally return `202 Accepted`; workflow creation returns `201 Created`
- Validation and conflict errors currently use plain-text response bodies

There is no API authentication or version prefix in the current implementation. Do not expose these endpoints to an untrusted network.

## Tasks

### Create a task

`POST /api/tasks`

```json
{
  "command": "/bin/sh",
  "arguments": ["-c", "printf hello"],
  "maxAttempts": 3,
  "timeoutSeconds": 30,
  "dependsOnTaskIds": ["optional-existing-task-id"]
}
```

| Field | Required | Default | Constraints |
| --- | --- | --- | --- |
| `command` | yes | — | Non-blank executable name or path |
| `arguments` | no | `[]` | Ordered string arguments; no shell parsing is performed by Forge |
| `maxAttempts` | no | `1` | 1–10 automatic attempts |
| `timeoutSeconds` | no | `0` | 0–86,400; `0` disables timeout |
| `dependsOnTaskIds` | no | `[]` | Existing task IDs; duplicates are removed while preserving order |
| `dependsOnTaskId` | no | `null` | Legacy single-dependency field, retained for compatibility |

Returns `202 Accepted` with a task response. A task without unresolved dependencies starts as `PENDING`; otherwise it starts as `BLOCKED`.

### List tasks

`GET /api/tasks`

Returns `200 OK` with all tasks.

### Get a task

`GET /api/tasks/{taskId}`

```json
{
  "id": "3a62...",
  "timeoutSeconds": 30,
  "command": "/bin/sh",
  "status": "RUNNING",
  "dependsOnTaskId": null,
  "dependsOnTaskIds": [],
  "cancelRequested": false,
  "workerId": "build-host-worker",
  "createdAt": "2026-09-07T13:00:00Z",
  "maxAttempts": 3,
  "exitCode": null,
  "stdout": null,
  "stderr": null
}
```

Returns `404 Not Found` for an unknown ID.

### List attempts

`GET /api/tasks/{taskId}/attempts`

Returns physical attempts in attempt order. Each attempt contains its ID, task ID, attempt number, worker and worker-session ownership, status, timestamps, exit code, stdout, and stderr.

### Cancel a task

`POST /api/tasks/{taskId}/cancel`

- Undispatched `CREATED`, `PENDING`, or `BLOCKED` work becomes `CANCELLED` immediately.
- `DISPATCHED` or `RUNNING` work records durable cancellation intent and sends `CancelTask` to the assigned worker.
- Repeating cancellation for an already cancelled task is idempotent.

Returns `202 Accepted`, `404 Not Found`, `409 Conflict` for an invalid state, or `503 Service Unavailable` when intent was recorded but delivery could not be completed.

### Retry a task

`POST /api/tasks/{taskId}/retry`

Creates and dispatches the next physical attempt when the logical task and latest attempt are `FAILED` or `LOST`. Automatic retries respect `maxAttempts`; this explicit recovery action is a separate operator decision.

Returns `202 Accepted`, `404 Not Found`, `409 Conflict` for an invalid state, or `503 Service Unavailable` when no worker is available.

## Workflows

### Create a workflow

`POST /api/workflows`

```json
{
  "name": "build-and-test",
  "tasks": [
    {
      "key": "build",
      "command": "/usr/bin/make",
      "arguments": ["all"],
      "maxAttempts": 2,
      "timeoutSeconds": 600,
      "dependsOn": []
    },
    {
      "key": "test",
      "command": "/usr/bin/make",
      "arguments": ["test"],
      "dependsOn": ["build"]
    }
  ]
}
```

Validation is transactional and occurs before tasks become visible:

- the name must be non-blank;
- a workflow contains 1–100 tasks;
- task keys are unique after trimming;
- every dependency names a task in the same request;
- self-dependencies and cycles are rejected;
- each task has at most 100 dependencies;
- task retry and timeout limits match the standalone task API.

Returns `201 Created`:

```json
{
  "id": "9e4f...",
  "name": "build-and-test",
  "createdAt": "2026-09-07T13:00:00Z",
  "status": "PENDING",
  "tasks": [
    {
      "key": "build",
      "taskId": "4a8d...",
      "status": "PENDING",
      "dependsOn": []
    },
    {
      "key": "test",
      "taskId": "7b10...",
      "status": "BLOCKED",
      "dependsOn": ["build"]
    }
  ]
}
```

### List workflows

`GET /api/workflows`

Returns newest-first summaries containing `id`, `name`, `createdAt`, derived `status`, and `taskCount`.

### Get a workflow

`GET /api/workflows/{workflowId}`

Returns the workflow with task keys, generated task IDs, states, and dependency keys.

### Cancel a workflow

`POST /api/workflows/{workflowId}/cancel`

Persists workflow-level cancellation intent before cancelling eligible child tasks. Successful nodes remain successful; inactive failed/lost nodes become cancelled; running work receives worker cancellation. The controller completes an interrupted cancellation sweep at startup.

Returns `202 Accepted`, `404 Not Found`, or `503 Service Unavailable` if part of the sweep cannot currently reach a worker.

### Retry a workflow

`POST /api/workflows/{workflowId}/retry`

Available only after the workflow has stopped progressing and automatic retries are exhausted. Successful work is preserved. Failed, lost, cancelled, and skipped nodes are reopened as `PENDING` or `BLOCKED` according to their dependencies in one transaction.

Returns `202 Accepted`, `404 Not Found`, or `400 Bad Request` when the workflow is active or has nothing to retry.

## Workers

### List workers

`GET /api/workers`

```json
[
  {
    "id": "build-host-worker",
    "sessionId": "process-session-uuid",
    "hostname": "build-host",
    "operatingSystem": "Linux",
    "cpuCores": 8,
    "memoryBytes": 17179869184,
    "cpuUsagePercent": 7.2,
    "memoryUsedBytes": 4294967296,
    "runningTasks": 1,
    "outstandingTasks": 1,
    "capacity": 4,
    "online": true,
    "commandStreamConnected": true,
    "lastHeartbeat": 1788786000000
  }
]
```

Workers are sorted by ID. This endpoint describes the controller's live in-memory registry; historical disconnected workers are not a separate durable resource.

## Execution timelines

| Endpoint | Timeline |
| --- | --- |
| `GET /api/tasks/{taskId}/events` | All events for a task |
| `GET /api/attempts/{attemptId}/events` | All events for one physical attempt |
| `GET /api/workflows/{workflowId}/events` | All events across a workflow |

Events are ordered by their monotonically increasing database ID:

```json
{
  "id": 42,
  "type": "TASK_RUNNING",
  "workflowId": "9e4f...",
  "taskId": "4a8d...",
  "attemptId": "1f07...",
  "workerId": "build-host-worker",
  "message": "Task accepted by worker",
  "createdAt": "2026-09-07T13:00:02Z"
}
```

Event types cover task and attempt transitions, retry eligibility, worker loss, workflow creation, workflow cancellation intent, and workflow retry intent. The canonical enum is `ExecutionEventType` in the controller source.

## Lifecycle values

**Task:** `CREATED`, `BLOCKED`, `PENDING`, `DISPATCHED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `LOST`, `CANCELLED`, `SKIPPED`

**Attempt:** `CREATED`, `DISPATCHED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `LOST`, `CANCELLED`

**Workflow:** `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`

## Worker gRPC contract

The shared contract lives at [`proto/forge.proto`](../proto/forge.proto). Java and C++ generated sources are build outputs and should not be edited directly.

### RPCs

| RPC | Shape | Purpose |
| --- | --- | --- |
| `RegisterWorker` | unary | Claim or revalidate a worker ID/session and advertise host capacity |
| `Heartbeat` | unary | Report live CPU, memory, and task metrics for the authoritative session |
| `ConnectWorker` | bidirectional stream | Carry assignments/cancellations to the worker and accepted/result events to the controller |

### Stream envelopes

`WorkerMessage` contains exactly one of `WorkerHello`, `WorkerHeartbeat`, `TaskAccepted`, or `TaskResult`. `ControllerMessage` contains exactly one of `TaskAssignment`, `CancelTask`, or `WorkerEventAck`.

The active implementation sends operational heartbeats through the unary `Heartbeat` RPC. Every stream begins with `WorkerHello`, and task lifecycle events include the owning session ID and deterministic event ID.
