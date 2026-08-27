package dev.forge.controller.task;

import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class TaskRegistry {

    private final ForgeTaskRepository repository;


    public TaskRegistry(
            ForgeTaskRepository repository) {

        this.repository =
                repository;
    }


    public ForgeTask register(
            ForgeTask task) {

        return repository.save(task);
    }


    public ForgeTask save(
            ForgeTask task) {

        return repository.save(task);
    }


    public ForgeTask get(
            String taskId) {

        return repository
                .findById(taskId)
                .orElse(null);
    }


    public List<ForgeTask> getAll() {

        return repository.findAll();
    }
}