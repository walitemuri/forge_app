package dev.forge.controller.api;

import dev.forge.controller.workflow.WorkflowStatus;

import java.time.Instant;


public record WorkflowSummaryResponse(
        String id,
        String name,
        Instant createdAt,
        WorkflowStatus status,
        int taskCount
) {
}
