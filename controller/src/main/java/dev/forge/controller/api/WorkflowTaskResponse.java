package dev.forge.controller.api;

import java.util.List;


public record WorkflowTaskResponse(
        String key,
        String taskId,
        String status,
        List<String> dependsOn
) {
}
