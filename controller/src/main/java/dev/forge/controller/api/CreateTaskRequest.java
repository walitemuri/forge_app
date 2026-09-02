package dev.forge.controller.api;

import java.util.List;

public record CreateTaskRequest(
        String command,
        List<String> arguments,
        Integer maxAttempts,
        Integer timeoutSeconds,

        // Temporary backwards compatibility.
        String dependsOnTaskId,

        // New DAG API.
        List<String> dependsOnTaskIds
) {
}