package dev.forge.controller.api;

import dev.forge.controller.workflow.WorkflowStatus;

import java.time.Instant;
import java.util.List;


public record WorkflowResponse(
        String id,
        String name,
        Instant createdAt,
        WorkflowStatus status,
        List<WorkflowTaskResponse> tasks
) {
}