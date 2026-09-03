package dev.forge.controller.api;

import java.time.Instant;
import java.util.List;


public record WorkflowResponse(
        String id,
        String name,
        Instant createdAt,
        List<WorkflowTaskResponse> tasks
) {
}
