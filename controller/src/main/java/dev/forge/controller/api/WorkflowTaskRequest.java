package dev.forge.controller.api;

import java.util.List;


public record WorkflowTaskRequest(
        String key,
        String command,
        List<String> arguments,
        Integer maxAttempts,
        Integer timeoutSeconds,
        List<String> dependsOn
) {
}
