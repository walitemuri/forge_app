package dev.forge.controller.api;


public record WorkerResponse(
        String id,
        String sessionId,
        String hostname,
        String operatingSystem,
        int cpuCores,
        long memoryBytes,
        double cpuUsagePercent,
        long memoryUsedBytes,
        int runningTasks,
        int outstandingTasks,
        int capacity,
        boolean online,
        boolean commandStreamConnected,
        long lastHeartbeat
) {
}