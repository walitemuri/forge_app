package dev.forge.controller.task;

import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class TaskAttemptRegistry {

    private final TaskAttemptRepository repository;


    public TaskAttemptRegistry(
            TaskAttemptRepository repository) {

        this.repository =
                repository;
    }


    public TaskAttempt save(
            TaskAttempt attempt) {

        return repository.save(
                attempt
        );
    }


    public TaskAttempt get(
            String attemptId) {

        return repository
                .findById(attemptId)
                .orElse(null);
    }


    public List<TaskAttempt> getForTask(
            String taskId) {

        return repository
                .findByTaskIdOrderByAttemptNumberAsc(
                        taskId
                );
    }
}

