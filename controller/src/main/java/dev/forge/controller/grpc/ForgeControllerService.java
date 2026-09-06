package dev.forge.controller.grpc;

import dev.forge.controller.task.ForgeTask;
import dev.forge.controller.task.TaskAttempt;
import dev.forge.controller.task.TaskAttemptRegistry;
import dev.forge.controller.task.TaskAttemptStatus;
import dev.forge.controller.task.TaskRegistry;

import dev.forge.controller.event.ExecutionEventService;
import dev.forge.controller.event.ExecutionEventType;

import dev.forge.proto.ControllerMessage;
import dev.forge.proto.ForgeControllerGrpc;
import dev.forge.proto.HeartbeatRequest;
import dev.forge.proto.HeartbeatResponse;
import dev.forge.proto.RegisterWorkerRequest;
import dev.forge.proto.RegisterWorkerResponse;
import dev.forge.proto.WorkerEventAck;
import dev.forge.proto.WorkerMessage;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import org.springframework.stereotype.Component;


@Component
public class ForgeControllerService
        extends ForgeControllerGrpc.ForgeControllerImplBase {

    private final TaskRegistry taskRegistry;
    private final TaskAttemptRegistry taskAttemptRegistry;

    private final ExecutionEventService executionEventService;


    public ForgeControllerService(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            ExecutionEventService executionEventService) {

        this.taskRegistry = taskRegistry;
        this.taskAttemptRegistry = taskAttemptRegistry;
        this.executionEventService = executionEventService;
    }


    // =========================================================
    // Worker registration
    // =========================================================

    @Override
    public void registerWorker(
            RegisterWorkerRequest request,
            StreamObserver<RegisterWorkerResponse> responseObserver) {

        WorkerState existing =
                WorkerRegistry.get(
                        request.getWorkerId()
                );


        /*
         * Same worker process reconnecting.
         *
         * Preserve reservations and current state instead of
         * replacing WorkerState with a fresh object.
         */
        if (existing != null
                && existing.hasSession(
                        request.getSessionId()
                )) {

            existing.refreshRegistration();


            RegisterWorkerResponse response =
                    RegisterWorkerResponse
                            .newBuilder()
                            .setAccepted(true)
                            .setMessage(
                                    "Worker session re-registered successfully"
                            )
                            .build();


            responseObserver.onNext(
                    response
            );

            responseObserver.onCompleted();

            return;
        }


        /*
         * Another process is already actively controlling this
         * stable worker ID.
         *
         * Do NOT let a second process steal ownership while the
         * first still has a live command stream.
         */
        if (existing != null
                && existing.isOnline()
                && existing.hasCommandStream()) {

            System.err.println(
                    "FENCED duplicate worker registration: worker="
                            + request.getWorkerId()
                            + " activeSession="
                            + existing.getSessionId()
                            + " rejectedSession="
                            + request.getSessionId()
            );


            RegisterWorkerResponse response =
                    RegisterWorkerResponse
                            .newBuilder()
                            .setAccepted(false)
                            .setMessage(
                                    "Worker ID is already owned by an active session"
                            )
                            .build();


            responseObserver.onNext(
                    response
            );

            responseObserver.onCompleted();

            return;
        }


        /*
         * Different session, but the previous incarnation no
         * longer owns a live command stream.
         *
         * IMPORTANT:
         *
         * Do NOT mark its attempts LOST here.
         *
         * The replacement process may have loaded a durable
         * TaskAccepted/TaskResult from the previous process's
         * disk outbox. It must get a chance to replay that event
         * before Forge decides the old execution is lost.
         *
         * Session-specific attempt ownership will be added in
         * the next step.
         */
        if (existing != null) {

            System.out.println(
                    "Worker session takeover: worker="
                            + request.getWorkerId()
                            + " oldSession="
                            + existing.getSessionId()
                            + " newSession="
                            + request.getSessionId()
            );


            existing.setOnline(
                    false
            );

            existing.setCommandStream(
                    null
            );
        }


        WorkerState worker = new WorkerState(
                request.getWorkerId(),
                request.getSessionId(),
                request.getHostname(),
                request.getCpuCores(),
                request.getMemoryBytes(),
                request.getOperatingSystem()
        );


        WorkerRegistry.register(
                worker
        );


        System.out.println();
        System.out.println("=== WORKER REGISTERED ===");
        System.out.println("ID:       " + request.getWorkerId());
        System.out.println("Session:  " + request.getSessionId());
        System.out.println("Hostname: " + request.getHostname());
        System.out.println("CPU:      " + request.getCpuCores() + " cores");
        System.out.println("Memory:   " + request.getMemoryBytes() + " bytes");
        System.out.println("OS:       " + request.getOperatingSystem());
        System.out.println("=========================");
        System.out.println();


        RegisterWorkerResponse response =
                RegisterWorkerResponse
                        .newBuilder()
                        .setAccepted(true)
                        .setMessage(
                                "Worker registered successfully"
                        )
                        .build();


        responseObserver.onNext(
                response
        );

        responseObserver.onCompleted();
    }


    // =========================================================
    // Heartbeat
    // =========================================================

    @Override
    public void heartbeat(
            HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {

        WorkerState worker =
                WorkerRegistry.get(
                        request.getWorkerId()
                );


        if (worker == null
                || !worker.hasSession(
                        request.getSessionId()
                )) {

            if (worker != null) {

                System.err.println(
                        "Ignoring heartbeat from stale session: worker="
                                + request.getWorkerId()
                                + " activeSession="
                                + worker.getSessionId()
                                + " staleSession="
                                + request.getSessionId()
                );
            }


            responseObserver.onNext(
                    HeartbeatResponse
                            .newBuilder()
                            .setAccepted(false)
                            .build()
            );

            responseObserver.onCompleted();

            return;
        }


        boolean wasOffline =
                !worker.isOnline();


        worker.updateHeartbeat(
                request.getCpuUsagePercent(),
                request.getMemoryUsedBytes(),
                request.getRunningTasks()
        );


        if (wasOffline) {

            System.out.println(
                    "✓ WORKER ONLINE: "
                            + request.getWorkerId()
            );
        }


        System.out.println(
                "[heartbeat] "
                        + request.getWorkerId()
                        + " CPU="
                        + String.format(
                                "%.1f",
                                request.getCpuUsagePercent()
                        )
                        + "% RAM="
                        + request.getMemoryUsedBytes()
                        + " TASKS="
                        + request.getRunningTasks()
        );


        responseObserver.onNext(
                HeartbeatResponse
                        .newBuilder()
                        .setAccepted(true)
                        .build()
        );


        responseObserver.onCompleted();
    }


    // =========================================================
    // Long-lived worker command stream
    // =========================================================

    @Override
    public StreamObserver<WorkerMessage> connectWorker(
            StreamObserver<ControllerMessage> responseObserver) {

        return new StreamObserver<>() {

            private String connectedWorkerId;
            private String connectedSessionId;
            private boolean fenced;


            /*
             * ACK a durable worker event.
             *
             * We deliberately send through WorkerState instead
             * of calling responseObserver.onNext() directly.
             *
             * WorkerState.sendCommand() is synchronized, which
             * serializes ACKs with TaskAssignment and CancelTask
             * writes on this same gRPC stream.
             */
            private void acknowledgeEvent(
                    String eventId) {

                if (eventId == null
                        || eventId.isBlank()) {

                    return;
                }


                if (connectedWorkerId == null) {

                    System.err.println(
                            "Cannot ACK worker event before WorkerHello: "
                                    + eventId
                    );

                    return;
                }


                WorkerState worker =
                        WorkerRegistry.get(
                                connectedWorkerId
                        );


                if (worker == null) {

                    System.err.println(
                            "Cannot ACK worker event for unknown worker: "
                                    + connectedWorkerId
                                    + " event="
                                    + eventId
                    );

                    return;
                }


                ControllerMessage ack =
                        ControllerMessage
                                .newBuilder()
                                .setEventAck(
                                        WorkerEventAck
                                                .newBuilder()
                                                .setEventId(
                                                        eventId
                                                )
                                                .build()
                                )
                                .build();


                if (!worker.sendCommand(
                        ack)) {

                    System.err.println(
                            "Failed to ACK worker event: "
                                    + eventId
                    );
                }
            }


            @Override
            public void onNext(
                    WorkerMessage message) {

                if (fenced) {

                    return;
                }


                /*
                 * Once Hello has established identity, verify on
                 * every subsequent message that this stream still
                 * belongs to the authoritative session.
                 */
                if (!message.hasHello()
                        && connectedWorkerId != null) {

                    WorkerState current =
                            WorkerRegistry.get(
                                    connectedWorkerId
                            );


                    if (current == null
                            || !current.hasSession(
                                    connectedSessionId
                            )) {

                        fenced =
                                true;


                        System.err.println(
                                "FENCED stale worker stream message: worker="
                                        + connectedWorkerId
                                        + " session="
                                        + connectedSessionId
                        );


                        responseObserver.onError(
                                Status.FAILED_PRECONDITION
                                        .withDescription(
                                                "Worker session is no longer authoritative"
                                        )
                                        .asRuntimeException()
                        );

                        return;
                    }
                }


                // =================================================
                // WorkerHello
                // =================================================

                if (message.hasHello()) {

                    connectedWorkerId =
                            message
                                    .getHello()
                                    .getWorkerId();

                    connectedSessionId =
                            message
                                    .getHello()
                                    .getSessionId();


                    WorkerState worker =
                            WorkerRegistry.get(
                                    connectedWorkerId
                            );


                    if (worker == null
                            || !worker.hasSession(
                                    connectedSessionId
                            )) {

                        fenced =
                                true;


                        System.err.println(
                                "FENCED stale command stream: worker="
                                        + connectedWorkerId
                                        + " session="
                                        + connectedSessionId
                        );


                        responseObserver.onError(
                                Status.FAILED_PRECONDITION
                                        .withDescription(
                                                "Stale worker session"
                                        )
                                        .asRuntimeException()
                        );

                        return;
                    }


                    worker.setCommandStream(
                            responseObserver
                    );


                    System.out.println(
                            "✓ COMMAND STREAM CONNECTED: "
                                    + connectedWorkerId
                    );


                    return;
                }


                // =================================================
                // TaskAccepted
                // =================================================

                if (message.hasTaskAccepted()) {

                    String taskId =
                            message
                                    .getTaskAccepted()
                                    .getTaskId();

                    String attemptId =
                            message
                                    .getTaskAccepted()
                                    .getAttemptId();

                    String eventId =
                            message
                                    .getTaskAccepted()
                                    .getEventId();

                    String eventSessionId =
                            message
                                    .getTaskAccepted()
                                    .getSessionId();


                    ForgeTask task =
                            taskRegistry.get(
                                    taskId
                            );

                    TaskAttempt attempt =
                            taskAttemptRegistry.get(
                                    attemptId
                            );


                    /*
                     * These cases are permanently non-actionable.
                     *
                     * ACK them so a malformed/stale event cannot
                     * remain in the worker outbox forever.
                     */
                    if (task == null) {

                        System.err.println(
                                "TaskAccepted for unknown task: "
                                        + taskId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    if (attempt == null) {

                        System.err.println(
                                "TaskAccepted for unknown attempt: "
                                        + attemptId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    if (!attempt
                            .getTaskId()
                            .equals(taskId)) {

                        System.err.println(
                                "Attempt/task mismatch: attempt="
                                        + attemptId
                                        + " task="
                                        + taskId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    TaskAttempt latestAttempt =
                            taskAttemptRegistry
                                    .getLatestForTask(
                                            taskId
                                    );


                    if (latestAttempt == null
                            || !latestAttempt
                            .getId()
                            .equals(attemptId)) {

                        System.err.println(
                                "Ignoring stale TaskAccepted: task="
                                        + taskId
                                        + " attempt="
                                        + attemptId
                        );


                        /*
                         * A newer attempt already exists.
                         * This old event must never mutate state.
                         */
                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    if (connectedWorkerId == null
                            || !connectedWorkerId
                            .equals(
                                    attempt.getWorkerId()
                            )) {

                        System.err.println(
                                "Ignoring TaskAccepted from wrong worker: task="
                                        + taskId
                                        + " attempt="
                                        + attemptId
                                        + " expectedWorker="
                                        + attempt.getWorkerId()
                                        + " actualWorker="
                                        + connectedWorkerId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    /*
                     * New attempts are owned by both a stable
                     * worker ID and the exact worker process
                     * session that received the assignment.
                     *
                     * A replacement worker session may RELAY an
                     * event recovered from the old process's
                     * durable outbox, but the event itself must
                     * still identify the owning session.
                     *
                     * Null ownership is allowed only for legacy
                     * pre-V16 attempts.
                     */
                    if (attempt.getWorkerSessionId() != null
                            && !attempt.getWorkerSessionId().isBlank()
                            && !attempt.getWorkerSessionId()
                                    .equals(eventSessionId)) {

                        System.err.println(
                                "Ignoring TaskAccepted from wrong session: task="
                                        + taskId
                                        + " attempt="
                                        + attemptId
                                        + " expectedSession="
                                        + attempt.getWorkerSessionId()
                                        + " eventSession="
                                        + eventSessionId
                                        + " relaySession="
                                        + connectedSessionId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    /*
                     * Duplicate replay:
                     *
                     * The first copy may have already changed the
                     * attempt to RUNNING, but its ACK could have
                     * been lost.
                     *
                     * Do not perform the state transition twice.
                     * Just ACK the replay.
                     */
                    if (attempt.getStatus()
                            != TaskAttemptStatus.DISPATCHED) {

                        System.err.println(
                                "Ignoring duplicate/stale TaskAccepted: "
                                        + attemptId
                                        + " status="
                                        + attempt.getStatus()
                        );


                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    /*
                     * Persist state BEFORE ACK.
                     */
                    attempt.markRunning();

                    taskAttemptRegistry.save(
                            attempt
                    );


                    task.markRunning();

                    taskRegistry.save(
                            task
                    );


                    executionEventService.record(
                            ExecutionEventType.ATTEMPT_RUNNING,
                            task.getWorkflowId(),
                            task.getId(),
                            attempt.getId(),
                            attempt.getWorkerId(),
                            "Worker accepted execution attempt"
                    );


                    executionEventService.record(
                            ExecutionEventType.TASK_RUNNING,
                            task.getWorkflowId(),
                            task.getId(),
                            attempt.getId(),
                            attempt.getWorkerId(),
                            "Task started running"
                    );


                    /*
                     * Only after durable state has been updated may
                     * the worker remove this event from its outbox.
                     */
                    acknowledgeEvent(
                            eventId
                    );


                    System.out.println(
                            "▶ TASK RUNNING: "
                                    + taskId
                                    + " attempt="
                                    + attemptId
                    );


                    return;
                }


                // =================================================
                // TaskResult
                // =================================================

                if (message.hasTaskResult()) {

                    var result =
                            message.getTaskResult();


                    String taskId =
                            result.getTaskId();

                    String attemptId =
                            result.getAttemptId();

                    String eventId =
                            result.getEventId();

                    String eventSessionId =
                            result.getSessionId();


                    ForgeTask task =
                            taskRegistry.get(
                                    taskId
                            );

                    TaskAttempt attempt =
                            taskAttemptRegistry.get(
                                    attemptId
                            );


                    if (task == null) {

                        System.err.println(
                                "TaskResult for unknown task: "
                                        + taskId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    if (attempt == null) {

                        System.err.println(
                                "TaskResult for unknown attempt: "
                                        + attemptId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    if (!attempt
                            .getTaskId()
                            .equals(taskId)) {

                        System.err.println(
                                "Attempt/task mismatch: attempt="
                                        + attemptId
                                        + " task="
                                        + taskId
                        );

                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    TaskAttempt latestAttempt =
                            taskAttemptRegistry
                                    .getLatestForTask(
                                            taskId
                                    );


                    if (latestAttempt == null
                            || !latestAttempt
                            .getId()
                            .equals(attemptId)) {

                        System.err.println(
                                "Ignoring stale TaskResult: task="
                                        + taskId
                                        + " attempt="
                                        + attemptId
                        );


                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    if (connectedWorkerId == null
                            || !connectedWorkerId
                            .equals(
                                    attempt.getWorkerId()
                            )) {

                        System.err.println(
                                "Ignoring TaskResult from wrong worker: task="
                                        + taskId
                                        + " attempt="
                                        + attemptId
                                        + " expectedWorker="
                                        + attempt.getWorkerId()
                                        + " actualWorker="
                                        + connectedWorkerId
                        );


                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    if (attempt.getWorkerSessionId() != null
                            && !attempt.getWorkerSessionId().isBlank()
                            && !attempt.getWorkerSessionId()
                                    .equals(eventSessionId)) {

                        System.err.println(
                                "Ignoring TaskResult from wrong session: task="
                                        + taskId
                                        + " attempt="
                                        + attemptId
                                        + " expectedSession="
                                        + attempt.getWorkerSessionId()
                                        + " eventSession="
                                        + eventSessionId
                                        + " relaySession="
                                        + connectedSessionId
                        );


                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    /*
                     * A result replay can arrive after the original
                     * event was already persisted.
                     *
                     * It can also arrive after restart recovery
                     * marked the attempt LOST.
                     *
                     * Either way, terminal state wins.
                     */
                    if (attempt.getStatus()
                            != TaskAttemptStatus.DISPATCHED
                            && attempt.getStatus()
                            != TaskAttemptStatus.RUNNING) {

                        System.err.println(
                                "Ignoring duplicate/stale TaskResult: "
                                        + attemptId
                                        + " status="
                                        + attempt.getStatus()
                        );


                        acknowledgeEvent(
                                eventId
                        );

                        return;
                    }


                    WorkerState worker =
                            WorkerRegistry.get(
                                    attempt.getWorkerId()
                            );


                    if (worker != null) {

                        worker.releaseTask();
                    }


                    // =============================================
                    // Cancellation result
                    // =============================================

                    if (result.getCancelled()) {

                        attempt.markCancelled(
                                result.getExitCode(),
                                result.getStdout(),
                                result.getStderr()
                        );


                        taskAttemptRegistry.save(
                                attempt
                        );


                        task.markCancelled(
                                result.getExitCode(),
                                result.getStdout(),
                                result.getStderr()
                        );


                        taskRegistry.save(
                                task
                        );
                    }

                    // =============================================
                    // Normal result
                    // =============================================

                    else {

                        attempt.complete(
                                result.getSuccess(),
                                result.getExitCode(),
                                result.getStdout(),
                                result.getStderr()
                        );


                        taskAttemptRegistry.save(
                                attempt
                        );


                        task.clearCancellationRequest();


                        task.complete(
                                result.getSuccess(),
                                result.getExitCode(),
                                result.getStdout(),
                                result.getStderr()
                        );


                        taskRegistry.save(
                                task
                        );
                    }


                    /*
                     * Record the terminal transition before ACKing
                     * the reliable worker event.
                     */
                    if (result.getCancelled()) {

                        executionEventService.record(
                                ExecutionEventType.ATTEMPT_CANCELLED,
                                task.getWorkflowId(),
                                task.getId(),
                                attempt.getId(),
                                attempt.getWorkerId(),
                                "Execution attempt cancelled"
                        );


                        executionEventService.record(
                                ExecutionEventType.TASK_CANCELLED,
                                task.getWorkflowId(),
                                task.getId(),
                                attempt.getId(),
                                attempt.getWorkerId(),
                                "Task cancelled"
                        );
                    }
                    else if (result.getSuccess()) {

                        executionEventService.record(
                                ExecutionEventType.ATTEMPT_SUCCEEDED,
                                task.getWorkflowId(),
                                task.getId(),
                                attempt.getId(),
                                attempt.getWorkerId(),
                                "Execution attempt succeeded"
                        );


                        executionEventService.record(
                                ExecutionEventType.TASK_SUCCEEDED,
                                task.getWorkflowId(),
                                task.getId(),
                                attempt.getId(),
                                attempt.getWorkerId(),
                                "Task succeeded"
                        );
                    }
                    else {

                        executionEventService.record(
                                ExecutionEventType.ATTEMPT_FAILED,
                                task.getWorkflowId(),
                                task.getId(),
                                attempt.getId(),
                                attempt.getWorkerId(),
                                "Execution attempt failed with exit code "
                                        + result.getExitCode()
                        );


                        executionEventService.record(
                                ExecutionEventType.TASK_FAILED,
                                task.getWorkflowId(),
                                task.getId(),
                                attempt.getId(),
                                attempt.getWorkerId(),
                                "Task failed with exit code "
                                        + result.getExitCode()
                        );
                    }


                    /*
                     * State and timeline are now persisted.
                     *
                     * The worker may now remove this result from
                     * its reliable outbox.
                     */
                    acknowledgeEvent(
                            eventId
                    );


                    System.out.println();
                    System.out.println(
                            "=== TASK FINISHED ==="
                    );

                    System.out.println(
                            "Task: "
                                    + taskId
                    );

                    System.out.println(
                            "Attempt: "
                                    + attemptId
                    );

                    System.out.println(
                            "Status: "
                                    + task.getStatus()
                    );

                    System.out.println(
                            "Exit code: "
                                    + result.getExitCode()
                    );

                    System.out.println(
                            "stdout:"
                    );

                    System.out.println(
                            result.getStdout()
                    );


                    if (!result
                            .getStderr()
                            .isEmpty()) {

                        System.out.println(
                                "stderr:"
                        );

                        System.out.println(
                                result.getStderr()
                        );
                    }


                    System.out.println(
                            "====================="
                    );


                    return;
                }
            }


            // =====================================================
            // Stream error
            // =====================================================

            @Override
            public void onError(
                    Throwable throwable) {

                System.err.println(
                        "Worker stream error: "
                                + connectedWorkerId
                                + " - "
                                + throwable.getMessage()
                );


                clearCommandStream();
            }


            // =====================================================
            // Stream completed
            // =====================================================

            @Override
            public void onCompleted() {

                System.out.println(
                        "Worker command stream closed: "
                                + connectedWorkerId
                );


                clearCommandStream();


                responseObserver.onCompleted();
            }


            // =====================================================
            // Stream cleanup
            // =====================================================

            private void clearCommandStream() {

                if (connectedWorkerId == null) {

                    return;
                }


                WorkerState worker =
                        WorkerRegistry.get(
                                connectedWorkerId
                        );


                if (worker != null) {

                    /*
                     * Only clear this stream if it is still the
                     * stream stored for this connection.
                     *
                     * A reconnect may already have installed a
                     * newer stream by the time an old stream's
                     * onError callback runs.
                     */
                    if (worker.getCommandStream()
                            == responseObserver) {

                        worker.setCommandStream(
                                null
                        );
                    }
                }
            }
        };
    }
}
