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

import java.util.concurrent.ConcurrentHashMap;


@Component
public class ForgeControllerService
        extends ForgeControllerGrpc.ForgeControllerImplBase {

    private final TaskRegistry taskRegistry;
    private final TaskAttemptRegistry taskAttemptRegistry;

    private final ExecutionEventService executionEventService;

    private final WorkerSessionRecoveryCoordinator
            workerSessionRecoveryCoordinator;

    private final WorkerAuthorityService
            workerAuthorityService;

    private final WorkerTakeoverService
            workerTakeoverService;

    private final WorkerSessionHistoryService
            workerSessionHistoryService;

    private final WorkerColdStartGrace
            workerColdStartGrace;


    /*
     * Registration is a multi-step ownership decision:
     *
     *     WorkerRegistry read
     *     durable authority read / CAS
     *     WorkerRegistry write
     *
     * Concurrent registrations for the SAME stable worker ID
     * must therefore execute as one critical section.
     *
     * Different worker IDs get different lock objects and can
     * still register concurrently.
     */
    private final ConcurrentHashMap<String, Object>
            workerRegistrationLocks =
            new ConcurrentHashMap<>();


    public ForgeControllerService(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            ExecutionEventService executionEventService,
            WorkerSessionRecoveryCoordinator
                    workerSessionRecoveryCoordinator,
            WorkerAuthorityService
                    workerAuthorityService,
            WorkerTakeoverService
                    workerTakeoverService,
            WorkerSessionHistoryService
                    workerSessionHistoryService,
            WorkerColdStartGrace
                    workerColdStartGrace) {

        this.taskRegistry = taskRegistry;
        this.taskAttemptRegistry = taskAttemptRegistry;
        this.executionEventService = executionEventService;
        this.workerSessionRecoveryCoordinator =
                workerSessionRecoveryCoordinator;

        this.workerAuthorityService =
                workerAuthorityService;

        this.workerTakeoverService =
                workerTakeoverService;

        this.workerSessionHistoryService =
                workerSessionHistoryService;

        this.workerColdStartGrace =
                workerColdStartGrace;
    }


    private Object workerRegistrationLock(
            String workerId) {

        return workerRegistrationLocks
                .computeIfAbsent(
                        workerId,
                        ignored ->
                                new Object()
                );
    }


    // =========================================================
    // Worker registration
    // =========================================================

    @Override
    public void registerWorker(
            RegisterWorkerRequest request,
            StreamObserver<RegisterWorkerResponse> responseObserver) {

        String workerId =
                request.getWorkerId();


        Object registrationLock =
                workerRegistrationLock(
                        workerId
                );


        synchronized (registrationLock) {

            registerWorkerLocked(
                    request,
                    responseObserver
            );
        }
    }


    /*
     * Must only be entered while holding this worker ID's
     * registration lock.
     */
    private void registerWorkerLocked(
            RegisterWorkerRequest request,
            StreamObserver<RegisterWorkerResponse> responseObserver) {

        String workerId =
                request.getWorkerId();

        String sessionId =
                request.getSessionId();


        WorkerState existing =
                WorkerRegistry.get(
                        workerId
                );


        String durableSession =
                workerAuthorityService
                        .getAuthoritativeSession(
                                workerId
                        );


        /*
         * A session that previously lost authority must never
         * later qualify as a fresh worker incarnation.
         *
         * The current authoritative session wins if the
         * database were ever inconsistent enough to contain it
         * in both places.
         */
        if (durableSession != null
                && !durableSession.equals(
                        sessionId
                )
                && workerSessionHistoryService
                        .isRetired(
                                workerId,
                                sessionId
                        )) {

            System.err.println(
                    "FENCED permanently retired worker session: worker="
                            + workerId
                            + " session="
                            + sessionId
            );


            RegisterWorkerResponse response =
                    RegisterWorkerResponse
                            .newBuilder()
                            .setAccepted(false)
                            .setMessage(
                                    "Worker session has been permanently retired"
                            )
                            .build();


            responseObserver.onNext(
                    response
            );

            responseObserver.onCompleted();

            return;
        }


        // =====================================================
        // Same-session reconnect
        // =====================================================

        if (existing != null
                && existing.hasSession(
                        sessionId
                )) {

            /*
             * A WorkerState should never disagree with durable
             * authority.
             *
             * The null case is permitted for the one-time
             * migration from the pre-V19 controller.
             */
            if (durableSession == null) {

                if (!workerAuthorityService
                        .claimIfUnowned(
                                workerId,
                                sessionId
                        )) {

                    RegisterWorkerResponse response =
                            RegisterWorkerResponse
                                    .newBuilder()
                                    .setAccepted(false)
                                    .setMessage(
                                            "Worker authority is owned by another session"
                                    )
                                    .build();

                    responseObserver.onNext(
                            response
                    );

                    responseObserver.onCompleted();

                    return;
                }
            }
            else if (!durableSession.equals(
                    sessionId)) {

                System.err.println(
                        "FENCED worker registration because "
                                + "durable authority disagrees "
                                + "with in-memory state: worker="
                                + workerId
                                + " durableSession="
                                + durableSession
                                + " requestedSession="
                                + sessionId
                );


                RegisterWorkerResponse response =
                        RegisterWorkerResponse
                                .newBuilder()
                                .setAccepted(false)
                                .setMessage(
                                        "Worker session is not durably authoritative"
                                )
                                .build();

                responseObserver.onNext(
                        response
                );

                responseObserver.onCompleted();

                return;
            }


            workerSessionRecoveryCoordinator
                    .cancelRecovery(
                            workerId,
                            sessionId
                    );


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


        // =====================================================
        // A different LIVE session already owns this worker ID
        // =====================================================

        if (existing != null
                && existing.isOnline()
                && existing.hasCommandStream()) {

            System.err.println(
                    "FENCED duplicate worker registration: worker="
                            + workerId
                            + " activeSession="
                            + existing.getSessionId()
                            + " rejectedSession="
                            + sessionId
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


        // =====================================================
        // Registry empty
        //
        // This is the critical controller-restart case.
        // =====================================================

        if (existing == null) {

            /*
             * Brand-new stable worker ID.
             */
            if (durableSession == null) {

                if (!workerAuthorityService
                        .claimIfUnowned(
                                workerId,
                                sessionId
                        )) {

                    String winner =
                            workerAuthorityService
                                    .getAuthoritativeSession(
                                            workerId
                                    );


                    System.err.println(
                            "FENCED initial worker registration race: worker="
                                    + workerId
                                    + " winner="
                                    + winner
                                    + " rejectedSession="
                                    + sessionId
                    );


                    RegisterWorkerResponse response =
                            RegisterWorkerResponse
                                    .newBuilder()
                                    .setAccepted(false)
                                    .setMessage(
                                            "Worker authority was claimed by another session"
                                    )
                                    .build();


                    responseObserver.onNext(
                            response
                    );

                    responseObserver.onCompleted();

                    return;
                }
            }

            /*
             * Controller restarted with a durable authority but
             * no in-memory WorkerState yet.
             *
             * First give the persisted process incarnation time
             * to reconnect.
             */
            else if (!durableSession.equals(
                    sessionId)) {

                if (workerColdStartGrace
                        .isActive()) {

                    System.out.println(
                            "Deferring cold-start worker takeover: worker="
                                    + workerId
                                    + " authoritativeSession="
                                    + durableSession
                                    + " requestedSession="
                                    + sessionId
                                    + " remainingGraceMs="
                                    + workerColdStartGrace
                                            .remainingMillis()
                    );


                    RegisterWorkerResponse response =
                            RegisterWorkerResponse
                                    .newBuilder()
                                    .setAccepted(false)
                                    .setMessage(
                                            "Waiting for persisted worker authority to reconnect"
                                    )
                                    .build();


                    responseObserver.onNext(
                            response
                    );

                    responseObserver.onCompleted();

                    return;
                }


                /*
                 * The persisted authority did not reconnect
                 * during controller-start grace.
                 *
                 * The new session has already passed the
                 * permanent retired-session check above, so it
                 * may now atomically supersede the absent owner.
                 */
                try {

                    workerTakeoverService
                            .takeover(
                                    workerId,
                                    durableSession,
                                    sessionId
                            );

                }
                catch (WorkerTakeoverConflictException exc) {

                    RegisterWorkerResponse response =
                            RegisterWorkerResponse
                                    .newBuilder()
                                    .setAccepted(false)
                                    .setMessage(
                                            "Worker authority changed concurrently"
                                    )
                                    .build();


                    responseObserver.onNext(
                            response
                    );

                    responseObserver.onCompleted();

                    return;
                }


                System.out.println(
                        "Cold-start worker authority takeover: worker="
                                + workerId
                                + " oldSession="
                                + durableSession
                                + " newSession="
                                + sessionId
                );


                durableSession =
                        sessionId;
            }
        }


        // =====================================================
        // Different session taking over an OFFLINE WorkerState
        // =====================================================

        else {

            String oldSession =
                    existing.getSessionId();


            /*
             * Migration safety: if the running controller has an
             * old WorkerState but V19 has no durable row yet,
             * initialize authority to that currently known owner
             * before attempting a transfer.
             */
            if (durableSession == null) {

                if (!workerAuthorityService
                        .claimIfUnowned(
                                workerId,
                                oldSession
                        )) {

                    RegisterWorkerResponse response =
                            RegisterWorkerResponse
                                    .newBuilder()
                                    .setAccepted(false)
                                    .setMessage(
                                            "Unable to establish previous worker authority"
                                    )
                                    .build();


                    responseObserver.onNext(
                            response
                    );

                    responseObserver.onCompleted();

                    return;
                }


                durableSession =
                        workerAuthorityService
                                .getAuthoritativeSession(
                                        workerId
                                );
            }


            /*
             * Never transfer authority from an in-memory owner
             * that PostgreSQL no longer recognizes.
             */
            if (!oldSession.equals(
                    durableSession)) {

                System.err.println(
                        "FENCED takeover from non-authoritative "
                                + "in-memory session: worker="
                                + workerId
                                + " inMemorySession="
                                + oldSession
                                + " durableSession="
                                + durableSession
                                + " requestedSession="
                                + sessionId
                );


                RegisterWorkerResponse response =
                        RegisterWorkerResponse
                                .newBuilder()
                                .setAccepted(false)
                                .setMessage(
                                        "Existing worker session is not the durable authority"
                                )
                                .build();


                responseObserver.onNext(
                        response
                );

                responseObserver.onCompleted();

                return;
            }


            System.out.println(
                    "Worker session takeover: worker="
                            + workerId
                            + " oldSession="
                            + oldSession
                            + " newSession="
                            + sessionId
            );


            /*
             * The recovery grace and the durable authority
             * transfer are one database transaction.
             *
             * Either:
             *
             *     recovery(A) + authority A -> B
             *
             * both commit, or neither does.
             */
            try {

                workerTakeoverService
                        .takeover(
                                workerId,
                                oldSession,
                                sessionId
                        );

            }
            catch (WorkerTakeoverConflictException exc) {

                System.err.println(
                        "FENCED worker takeover because authority "
                                + "changed concurrently: worker="
                                + workerId
                                + " expectedSession="
                                + oldSession
                                + " requestedSession="
                                + sessionId
                );


                RegisterWorkerResponse response =
                        RegisterWorkerResponse
                                .newBuilder()
                                .setAccepted(false)
                                .setMessage(
                                        "Worker authority changed concurrently"
                                )
                                .build();


                responseObserver.onNext(
                        response
                );

                responseObserver.onCompleted();

                return;
            }


            existing.setOnline(
                    false
            );

            existing.disconnectCommandStream(
                    "Worker session superseded by new authority"
            );
        }


        // =====================================================
        // Registration accepted
        // =====================================================

        WorkerState worker =
                new WorkerState(
                        workerId,
                        sessionId,
                        request.getHostname(),
                        request.getCpuCores(),
                        request.getMemoryBytes(),
                        request.getOperatingSystem()
                );


        WorkerRegistry.register(
                worker
        );


        /*
         * If this exact session had an obsolete recovery row
         * from some prior lifecycle, it is authoritative again
         * and that row must not later mark its work LOST.
         */
        workerSessionRecoveryCoordinator
                .cancelRecovery(
                        workerId,
                        sessionId
                );


        System.out.println();
        System.out.println(
                "=== WORKER REGISTERED ==="
        );

        System.out.println(
                "ID:       "
                        + workerId
        );

        System.out.println(
                "Session:  "
                        + sessionId
        );

        System.out.println(
                "Hostname: "
                        + request.getHostname()
        );

        System.out.println(
                "CPU:      "
                        + request.getCpuCores()
                        + " cores"
        );

        System.out.println(
                "Memory:   "
                        + request.getMemoryBytes()
                        + " bytes"
        );

        System.out.println(
                "OS:       "
                        + request.getOperatingSystem()
        );

        System.out.println(
                "========================="
        );

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


                    /*
                     * Registration/takeover and command-stream
                     * attachment must agree on one atomic view of
                     * the stable worker ID.
                     *
                     * Without this lock a stale WorkerHello could
                     * validate session A, pause, let session C take
                     * authority, and then attach A's stream to the
                     * detached old WorkerState.
                     */
                    Object registrationLock =
                            workerRegistrationLock(
                                    connectedWorkerId
                            );


                    synchronized (registrationLock) {

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
                    }


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
