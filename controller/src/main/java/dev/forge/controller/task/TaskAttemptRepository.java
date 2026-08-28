package dev.forge.controller.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;


public interface TaskAttemptRepository
        extends JpaRepository<TaskAttempt, String> {

    List<TaskAttempt>
    findByTaskIdOrderByAttemptNumberAsc(
            String taskId
    );


    List<TaskAttempt> findByStatusIn(
            Collection<TaskAttemptStatus> statuses
    );


    List<TaskAttempt> findByWorkerIdAndStatusIn(
            String workerId,
            Collection<TaskAttemptStatus> statuses
    );
}