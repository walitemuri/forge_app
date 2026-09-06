package dev.forge.controller.api;

import dev.forge.controller.event.ExecutionEvent;

import java.time.Instant;


public record ExecutionEventResponse(

        Long id,

        String type,

        String workflowId,

        String taskId,

        String attemptId,

        String workerId,

        String message,

        Instant createdAt

) {

    public static ExecutionEventResponse from(
            ExecutionEvent event) {

        return new ExecutionEventResponse(

                event.getId(),

                event.getEventType().name(),

                event.getWorkflowId(),

                event.getTaskId(),

                event.getAttemptId(),

                event.getWorkerId(),

                event.getMessage(),

                event.getCreatedAt()
        );
    }
}
