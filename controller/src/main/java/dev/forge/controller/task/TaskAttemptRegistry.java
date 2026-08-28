package dev.forge.controller.task;

import org.springframework.stereotype.Component;

import java.util.Collection;
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


    public List<TaskAttempt> saveAll(
            Collection<TaskAttempt> attempts) {

        return repository.saveAll(
                attempts
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


    public List<TaskAttempt> getByStatuses(
            Collection<TaskAttemptStatus> statuses) {

        return repository.findByStatusIn(
                statuses
        );
    }
}