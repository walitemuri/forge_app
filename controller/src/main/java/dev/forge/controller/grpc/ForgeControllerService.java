package dev.forge.controller.grpc;

import dev.forge.controller.task.ForgeTask;
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
public class ForgeControllerService
        extends ForgeControllerGrpc.ForgeControllerImplBase {

    private final TaskRegistry taskRegistry;


    public ForgeControllerService(
            TaskRegistry taskRegistry) {

        this.taskRegistry = taskRegistry;
    }


    // =========================================================
    // Worker registration
    // =========================================================

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


        RegisterWorkerResponse response =
                RegisterWorkerResponse
                        .newBuilder()
                        .setAccepted(true)
                        .setMessage(
                                "Worker registered successfully"
                        )
                        .build();


        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    // =========================================================
    // Worker heartbeat
    // =========================================================

    @Override
    public void heartbeat(
            HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {

        WorkerState worker =
                WorkerRegistry.get(
                        request.getWorkerId()
                );


        if (worker == null) {

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


        /*
         * RUNNING:
         *     tasks currently executing on the worker
         *
         * OUTSTANDING:
         *     tasks assigned by the controller that have
         *     not completed yet
         *
         * LOAD:
         *     effective scheduler load
         */
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
                        + " RUNNING="
                        + request.getRunningTasks()
                        + " OUTSTANDING="
                        + worker.getOutstandingTasks()
                        + " LOAD="
                        + worker.getEffectiveLoad()
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


            @Override
            public void onNext(
                    WorkerMessage message) {

                // =============================================
                // Worker connected
                // =============================================

                if (message.hasHello()) {

                    connectedWorkerId =
                            message
                                    .getHello()
                                    .getWorkerId();


                    WorkerState worker =
                            WorkerRegistry.get(
                                    connectedWorkerId
                            );


                    if (worker == null) {

                        System.err.println(
                                "Unknown worker attempted stream connection: "
                                        + connectedWorkerId
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
                }


                // =============================================
                // Worker accepted task
                // =============================================

                if (message.hasTaskAccepted()) {

                    String taskId =
                            message
                                    .getTaskAccepted()
                                    .getTaskId();


                    ForgeTask task =
                            taskRegistry.get(
                                    taskId
                            );


                    if (task != null) {

                        task.markRunning();


                        System.out.println(
                                "▶ TASK RUNNING: "
                                        + taskId
                        );
                    }
                }


                // =============================================
                // Worker completed task
                // =============================================

                if (message.hasTaskResult()) {

                    var result =
                            message.getTaskResult();


                    ForgeTask task =
                            taskRegistry.get(
                                    result.getTaskId()
                            );


                    if (task != null) {

                        /*
                         * This task no longer counts against the
                         * worker's controller-side reservation.
                         */
                        WorkerState worker =
                                WorkerRegistry.get(
                                        task.getWorkerId()
                                );


                        if (worker != null) {

                            worker.releaseTask();
                        }


                        task.complete(
                                result.getSuccess(),
                                result.getExitCode(),
                                result.getStdout(),
                                result.getStderr()
                        );


                        System.out.println();
                        System.out.println(
                                "=== TASK FINISHED ==="
                        );

                        System.out.println(
                                "Task: "
                                        + result.getTaskId()
                        );

                        System.out.println(
                                "Worker: "
                                        + task.getWorkerId()
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


                        if (!result.getStderr().isEmpty()) {

                            System.out.println(
                                    "stderr:"
                            );

                            System.out.println(
                                    result.getStderr()
                            );
                        }


                        if (worker != null) {

                            System.out.println(
                                    "Worker outstanding: "
                                            + worker.getOutstandingTasks()
                            );

                            System.out.println(
                                    "Worker effective load: "
                                            + worker.getEffectiveLoad()
                            );
                        }


                        System.out.println(
                                "====================="
                        );
                    }
                }
            }


            // =================================================
            // Stream failure
            // =================================================

            @Override
            public void onError(
                    Throwable throwable) {

                System.err.println(
                        "Worker stream error: "
                                + connectedWorkerId
                                + " - "
                                + throwable.getMessage()
                );


                if (connectedWorkerId != null) {

                    WorkerState worker =
                            WorkerRegistry.get(
                                    connectedWorkerId
                            );


                    if (worker != null) {

                        worker.setCommandStream(
                                null
                        );
                    }
                }
            }


            // =================================================
            // Stream closed normally
            // =================================================

            @Override
            public void onCompleted() {

                System.out.println(
                        "Worker command stream closed: "
                                + connectedWorkerId
                );


                if (connectedWorkerId != null) {

                    WorkerState worker =
                            WorkerRegistry.get(
                                    connectedWorkerId
                            );


                    if (worker != null) {

                        worker.setCommandStream(
                                null
                        );
                    }
                }


                responseObserver.onCompleted();
            }
        };
    }
}