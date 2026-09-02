package dev.forge.controller.api;

import java.util.List;


public record CreateTaskRequest(
        String command,
        List<String> arguments,
        Integer maxAttempts,
        Integer timeoutSeconds,
        String dependsOnTaskId
) {
}