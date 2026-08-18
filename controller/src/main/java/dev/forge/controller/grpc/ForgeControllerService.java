package dev.forge.controller.grpc;

import dev.forge.proto.ControllerMessage;
import dev.forge.proto.ForgeControllerGrpc;
import dev.forge.proto.HeartbeatRequest;
import dev.forge.proto.HeartbeatResponse;
import dev.forge.proto.RegisterWorkerRequest;
import dev.forge.proto.RegisterWorkerResponse;
import dev.forge.proto.WorkerMessage;

import io.grpc.stub.StreamObserver;

public class ForgeControllerService
        extends ForgeControllerGrpc.ForgeControllerImplBase {

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
                RegisterWorkerResponse.newBuilder()
                        .setAccepted(true)
                        .setMessage("Worker registered successfully")
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void heartbeat(
            HeartbeatRequest request,
            StreamObserver<HeartbeatResponse> responseObserver) {

        WorkerState worker =
                WorkerRegistry.get(request.getWorkerId());

        if (worker == null) {

            responseObserver.onNext(
                    HeartbeatResponse.newBuilder()
                            .setAccepted(false)
                            .build()
            );

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
        );

        responseObserver.onNext(
                HeartbeatResponse.newBuilder()
                        .setAccepted(true)
                        .build()
        );

        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<WorkerMessage> connectWorker(
            StreamObserver<ControllerMessage> responseObserver) {

        return new StreamObserver<>() {

            private String connectedWorkerId;

            @Override
            public void onNext(WorkerMessage message) {

                if (message.hasHello()) {

                    connectedWorkerId =
                            message.getHello().getWorkerId();

                    WorkerState worker =
                            WorkerRegistry.get(connectedWorkerId);

                    if (worker == null) {

                        System.err.println(
                                "Unknown worker attempted stream connection: "
                                        + connectedWorkerId
                        );

                        return;
                    }

                    worker.setCommandStream(responseObserver);

                    System.out.println(
                            "✓ COMMAND STREAM CONNECTED: "
                                    + connectedWorkerId
                    );
                }

                if (message.hasTaskAccepted()) {

                    System.out.println(
                            "Task accepted: "
                                    + message
                                    .getTaskAccepted()
                                    .getTaskId()
                    );
                }

                if (message.hasTaskResult()) {

                    System.out.println(
                            "Task completed: "
                                    + message
                                    .getTaskResult()
                                    .getTaskId()
                    );
                }
            }

            @Override
            public void onError(Throwable throwable) {

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
                        worker.setCommandStream(null);
                    }
                }
            }

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
                        worker.setCommandStream(null);
                    }
                }

                responseObserver.onCompleted();
            }
        };
    }
}