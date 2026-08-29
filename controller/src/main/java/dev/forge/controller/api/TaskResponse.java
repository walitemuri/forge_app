package dev.forge.controller.api;

import dev.forge.controller.task.ForgeTask;

import java.time.Instant;


public record TaskResponse(

        String id,
        
        int timeoutSeconds,

        String command,

        String status,

        boolean cancelRequested,
        String workerId,

        Instant createdAt,

        int maxAttempts,

        Integer exitCode,

        String stdout,

        String stderr
        

) {

    public static TaskResponse from(
            ForgeTask task) {

        return new TaskResponse(

                task.getId(),

                task.getTimeoutSeconds(),

                task.getCommand(),

                task.getStatus().name(),

                task.isCancelRequested(),
                task.getWorkerId(),

                task.getCreatedAt(),

                task.getMaxAttempts(),

                task.getExitCode(),

                task.getStdout(),
                
                task.getStderr()

        );
    }
}