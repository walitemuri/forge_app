
package dev.forge.controller.api;

import dev.forge.controller.task.ForgeTask;

import java.time.Instant;

public record TaskResponse(
        String id,
        String command,
        String status,
        String workerId,
        Instant createdAt
) {

    public static TaskResponse from(
            ForgeTask task) {

        return new TaskResponse(
                task.getId(),
                task.getCommand(),
                task.getStatus().name(),
                task.getWorkerId(),
                task.getCreatedAt()
        );
    }
}