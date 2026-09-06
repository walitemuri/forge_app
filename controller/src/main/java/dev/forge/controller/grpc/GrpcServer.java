package dev.forge.controller.grpc;

import dev.forge.controller.task.TaskRecoveryService;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

import dev.forge.controller.workflow.WorkflowCancellationRecoveryService;

@Component
public class GrpcServer {

    private final ForgeControllerService
            forgeControllerService;

    private final TaskRecoveryService
            taskRecoveryService;
    
    private final WorkflowCancellationRecoveryService
        workflowCancellationRecoveryService;

    private final int port;

    private Server server;


    public GrpcServer(
            ForgeControllerService forgeControllerService,
            TaskRecoveryService taskRecoveryService,
            WorkflowCancellationRecoveryService
                    workflowCancellationRecoveryService,
            @Value("${forge.grpc.port:50051}") int port) {

        this.forgeControllerService =
                forgeControllerService;

        this.taskRecoveryService =
                taskRecoveryService;

        this.workflowCancellationRecoveryService =
                workflowCancellationRecoveryService;

        this.port =
                port;
    }


    @PostConstruct
    public void start() throws IOException {

        /*
         * Resolve persisted tasks left in an in-flight state
         * before accepting new worker connections.
         */
        taskRecoveryService
                .recoverInterruptedTasks();

/*
        * Resolve workflows whose durable cancellation
        * intent survived a previous controller crash.
        *
        * This deliberately runs AFTER task recovery so
        * interrupted executions have already become LOST.
        */
        workflowCancellationRecoveryService
        .recoverCancelledWorkflows();
        server =
                ServerBuilder
                        .forPort(port)
                        .addService(
                                forgeControllerService
                        )
                        .build()
                        .start();


        System.out.println();
        System.out.println(
                "Forge gRPC server started on port "
                        + server.getPort()
        );
        System.out.println();
    }


    @PreDestroy
    public void stop() {

        if (server != null) {

            server.shutdown();
        }
    }
}