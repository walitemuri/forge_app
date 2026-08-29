package dev.forge.controller.grpc;

import dev.forge.controller.task.ForgeTask;
import dev.forge.controller.task.TaskAttempt;
import dev.forge.controller.task.TaskAttemptRegistry;
import dev.forge.controller.task.TaskRegistry;
import dev.forge.proto.ControllerMessage;
import dev.forge.proto.ForgeControllerGrpc;
import dev.forge.proto.HeartbeatRequest;
import dev.forge.proto.HeartbeatResponse;
import dev.forge.proto.RegisterWorkerRequest;
import dev.forge.proto.RegisterWorkerResponse;
import dev.forge.proto.WorkerMessage;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class ForgeControllerService extends ForgeControllerGrpc.ForgeControllerImplBase {

    private final TaskRegistry taskRegistry;
    private final TaskAttemptRegistry taskAttemptRegistry;
   
    public ForgeControllerService(
        TaskRegistry taskRegistry,
        TaskAttemptRegistry taskAttemptRegistry) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;
    }

    // ============================================================
    // Worker registration
    // ============================================================

    @Override
    public void registerWorker(
            RegisterWorkerRequest request,
            StreamObserver<RegisterWorkerResponse> responseObserver) {

        WorkerState worker = new WorkerState(
                request.getWorkerId(),
                request.getHostname(),
                request.getCpuCores(),
                request.getMemoryBytes(),
                request.getOperatingSystem()
        );

        WorkerRegistry.register(worker);

        System.out.println();
        System.out.println("=== WORKER REGISTERED ===");
        System.out.println("ID:       " + request.getWorkerId());
        System.out.println("Hostname: " + request.getHostname());
        System.out.println("CPU:      " + request.getCpuCores() + " cores");
        System.out.println("Memory:   " + request.getMemoryBytes() + " bytes");
        System.out.println("OS:       " + request.getOperatingSystem());
        System.out.println("=========================");
        System.out.println();

        RegisterWorkerResponse response = RegisterWorkerResponse.newBuilder()
                .setAccepted(true)
                .setMessage("Worker registered successfully")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // ============================================================
    // Worker heartbeat
    // ============================================================

    @Override
    public void heartbeat(
            HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {

        WorkerState worker = WorkerRegistry.get(request.getWorkerId());

        if (worker == null) {
            responseObserver.onNext(HeartbeatResponse.newBuilder()
                    .setAccepted(false)
                    .build());
            responseObserver.onCompleted();
            return;
        }

        boolean wasOffline = !worker.isOnline();

        worker.updateHeartbeat(
                request.getCpuUsagePercent(),
                request.getMemoryUsedBytes(),
                request.getRunningTasks()
        );

        if (wasOffline) {
            System.out.println("✓ WORKER ONLINE: " + request.getWorkerId());
        }

        System.out.println(String.format(
                "[heartbeat] %s CPU=%.1f%% RAM=%d RUNNING=%d OUTSTANDING=%d LOAD=%d",
                request.getWorkerId(),
                request.getCpuUsagePercent(),
                request.getMemoryUsedBytes(),
                request.getRunningTasks(),
                worker.getOutstandingTasks(),
                worker.getEffectiveLoad()
        ));

        responseObserver.onNext(HeartbeatResponse.newBuilder()
                .setAccepted(true)
                .build());
        responseObserver.onCompleted();
    }

    // ============================================================
    // Long-lived worker command stream
    // ============================================================

    @Override
    public StreamObserver<WorkerMessage> connectWorker(
            StreamObserver<ControllerMessage> responseObserver) {

        return new StreamObserver<>() {

            private String connectedWorkerId;

            // ====================================================
            // Incoming worker message
            // ====================================================

            @Override
            public void onNext(WorkerMessage message) {

                // ------------------------------------------------
                // WorkerHello
                // ------------------------------------------------

                if (message.hasHello()) {
                    connectedWorkerId = message.getHello().getWorkerId();
                    WorkerState worker = WorkerRegistry.get(connectedWorkerId);

                    if (worker == null) {
                        System.err.println("Unknown worker attempted stream connection: " + connectedWorkerId);
                        return;
                    }

                    worker.setCommandStream(responseObserver);
                    System.out.println("✓ COMMAND STREAM CONNECTED: " + connectedWorkerId);
                }

                // ------------------------------------------------
                // TaskAccepted
                // ------------------------------------------------

                if (message.hasTaskAccepted()) {

                        String taskId =
                                message
                                        .getTaskAccepted()
                                        .getTaskId();


                        String attemptId =
                                message
                                        .getTaskAccepted()
                                        .getAttemptId();


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
                                        "TaskAccepted for unknown task: "
                                                + taskId
                                );

                                return;
                        }


                        if (attempt == null) {

                                System.err.println(
                                        "TaskAccepted for unknown attempt: "
                                                + attemptId
                                );

                                return;
                        }


                        if (!attempt.getTaskId().equals(taskId)) {

                                        System.err.println(
                                                "Attempt/task mismatch: attempt="
                                                        + attemptId
                                                        + " task="
                                                        + taskId
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

                        return;
                        }


                        if (connectedWorkerId == null
                                || !connectedWorkerId.equals(
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

                        return;
                        }

                        attempt.markRunning();

                        taskAttemptRegistry.save(
                                attempt
                        );


                        task.markRunning();

                        taskRegistry.save(
                                task
                        );


                        System.out.println(
                                "▶ TASK RUNNING: "
                                        + taskId
                                        + " attempt="
                                        + attemptId
                        );
                }
                // ------------------------------------------------
                // TaskResult
                // ------------------------------------------------

                if (message.hasTaskResult()) {

                        var result =
                                message.getTaskResult();


                        String taskId =
                                result.getTaskId();

                        String attemptId =
                                result.getAttemptId();


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

                                return;
                        }


                        if (attempt == null) {

                                System.err.println(
                                        "TaskResult for unknown attempt: "
                                                + attemptId
                                );

                                return;
                        }


                        if (!attempt.getTaskId().equals(taskId)) {

                                System.err.println(
                                        "Attempt/task mismatch: attempt="
                                                + attemptId
                                                + " task="
                                                + taskId
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

                        return;
                        }


                        if (connectedWorkerId == null
                                || !connectedWorkerId.equals(
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

                        return;
                        }

                        WorkerState worker =
                                WorkerRegistry.get(
                                        attempt.getWorkerId()
                                );


                        if (worker != null) {

                                worker.releaseTask();
                        }


                        /*
                        * Finish the physical execution attempt.
                        */
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


                        /*
                        * Cancellation may have raced with natural
                        * completion. If the task completed first,
                        * the actual completion wins.
                        */
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
                                "Worker: "
                                        + attempt.getWorkerId()
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

                       System.out.println(
                                "====================="
                        );

                        System.out.println();
                        }

                        /*
                        * End of onNext(...)
                        */
}

                        // ====================================================
                        // Worker stream error
                        // ====================================================

                @Override
                public void onError(Throwable throwable) {
                System.err.println("Worker stream error: " + connectedWorkerId + " - " + throwable.getMessage());

                if (connectedWorkerId != null) {
                    WorkerState worker = WorkerRegistry.get(connectedWorkerId);
                    if (worker != null) {
                        worker.setCommandStream(null);
                    }
                }
            }

            // ====================================================
            // Worker gracefully closed stream
            // ====================================================

            @Override
            public void onCompleted() {
                System.out.println("Worker command stream closed: " + connectedWorkerId);

                if (connectedWorkerId != null) {
                    WorkerState worker = WorkerRegistry.get(connectedWorkerId);
                    if (worker != null) {
                        worker.setCommandStream(null);
                    }
                }

                responseObserver.onCompleted();
            }
        };
    }
}