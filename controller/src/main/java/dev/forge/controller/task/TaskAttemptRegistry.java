package dev.forge.controller.task;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
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
                .findById(
                        attemptId
                )
                .orElse(
                        null
                );
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

        return repository
                .findByStatusIn(
                        statuses
                );
    }


    public TaskAttempt getLatestForTask(
            String taskId) {

        List<TaskAttempt> attempts =
                getForTask(
                        taskId
                );


        if (attempts.isEmpty()) {

            return null;
        }


        return attempts.get(
                attempts.size() - 1
        );
    }


    public List<TaskAttempt> getActiveForWorker(
            String workerId) {

        return repository
                .findByWorkerIdAndStatusIn(
                        workerId,
                        EnumSet.of(
                                TaskAttemptStatus.CREATED,
                                TaskAttemptStatus.DISPATCHED,
                                TaskAttemptStatus.RUNNING
                        )
                );
    }


    public List<TaskAttempt> getActiveForWorkerSession(
            String workerId,
            String workerSessionId) {

        return repository
                .findByWorkerIdAndWorkerSessionIdAndStatusIn(
                        workerId,
                        workerSessionId,
                        EnumSet.of(
                                TaskAttemptStatus.CREATED,
                                TaskAttemptStatus.DISPATCHED,
                                TaskAttemptStatus.RUNNING
                        )
                );
    }
}
