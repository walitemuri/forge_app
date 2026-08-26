package dev.forge.controller.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import java.io.IOException;


@Component
public class GrpcServer {

    private final ForgeControllerService forgeControllerService;

    private Server server;


    public GrpcServer(
            ForgeControllerService forgeControllerService) {

        this.forgeControllerService =
                forgeControllerService;
    }


    @PostConstruct
    public void start() throws IOException {

        int port = 50051;


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
            + port
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