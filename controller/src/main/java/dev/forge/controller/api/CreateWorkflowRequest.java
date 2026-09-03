package dev.forge.controller.api;

import java.util.List;


public record CreateWorkflowRequest(
        String name,
        List<WorkflowTaskRequest> tasks
) {
}
