package dev.forge.controller.grpc;


public class WorkerTakeoverConflictException
        extends RuntimeException {

    public WorkerTakeoverConflictException(
            String message) {

        super(message);
    }
}
