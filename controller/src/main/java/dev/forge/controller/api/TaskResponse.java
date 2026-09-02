package dev.forge.controller.api;

import dev.forge.controller.task.ForgeTask;

import java.time.Instant;
import java.util.List;


public record TaskResponse(

        String id,

        int timeoutSeconds,

        String command,

        String status,

        // Temporary backwards-compatible field.
        String dependsOnTaskId,

        // New DAG field.
        List<String> dependsOnTaskIds,

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

        List<String> dependencies =
                task.getDependsOnTaskIds();


        /*
         * Preserve the old API field only when
         * there is exactly one dependency.
         */
        String legacyDependency =
                dependencies.size() == 1
                        ? dependencies.get(0)
                        : null;


        return new TaskResponse(

                task.getId(),

                task.getTimeoutSeconds(),

                task.getCommand(),

                task.getStatus().name(),

                legacyDependency,

                dependencies,

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