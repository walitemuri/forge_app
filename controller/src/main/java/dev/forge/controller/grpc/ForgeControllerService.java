package dev.forge.controller.grpc;

import dev.forge.proto.ForgeControllerGrpc;
import dev.forge.proto.RegisterWorkerRequest;
import dev.forge.proto.RegisterWorkerResponse;
import io.grpc.stub.StreamObserver;
import dev.forge.proto.HeartbeatRequest;
import dev.forge.proto.HeartbeatResponse;

public class ForgeControllerService
        extends ForgeControllerGrpc.ForgeControllerImplBase {

    @Override
    public void registerWorker(
            RegisterWorkerRequest request,
            StreamObserver<RegisterWorkerResponse> responseObserver) {

        WorkerState workerState = new WorkerState(
                request.getWorkerId(),
                request.getHostname(),
                request.getCpuCores(),
                request.getMemoryBytes(),
                request.getOperatingSystem());

        WorkerRegistry.register(workerState);

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

        worker.updateHeartbeat(
                request.getCpuUsagePercent(),
                request.getMemoryUsedBytes(),
                request.getRunningTasks()
        );

        System.out.println(
                "[heartbeat] "
                + request.getWorkerId()
                + " CPU="
                + request.getCpuUsagePercent()
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
}