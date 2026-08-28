package dev.forge.controller.grpc;

import dev.forge.controller.task.TaskRecoveryService;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;


@Component
public class GrpcServer {

    private final ForgeControllerService
            forgeControllerService;

    private final TaskRecoveryService
            taskRecoveryService;

    private final int port;

    private Server server;


    public GrpcServer(
            ForgeControllerService forgeControllerService,
            TaskRecoveryService taskRecoveryService,
            @Value("${forge.grpc.port:50051}") int port) {

        this.forgeControllerService =
                forgeControllerService;

        this.taskRecoveryService =
                taskRecoveryService;

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