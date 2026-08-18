package dev.forge.controller.task;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskRegistry {

    private final Map<String, ForgeTask> tasks =
            new ConcurrentHashMap<>();

    public void register(ForgeTask task) {
        tasks.put(task.getId(), task);
    }

    public ForgeTask get(String taskId) {
        return tasks.get(taskId);
    }

    public Collection<ForgeTask> getAll() {
        return tasks.values();
    }
}