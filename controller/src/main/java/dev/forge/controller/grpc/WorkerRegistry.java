package dev.forge.controller.grpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkerRegistry {

    private static final Map<String, WorkerState> workers =
            new ConcurrentHashMap<>();

    public static void register(WorkerState worker) {
        workers.put(worker.getWorkerId(), worker);
    }

    public static WorkerState get(String workerId) {
        return workers.get(workerId);
    }

    public static Map<String, WorkerState> getWorkers() {
        return workers;
    }
}