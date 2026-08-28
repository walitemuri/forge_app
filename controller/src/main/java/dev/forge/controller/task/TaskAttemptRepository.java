package dev.forge.controller.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface TaskAttemptRepository
        extends JpaRepository<TaskAttempt, String> {

    List<TaskAttempt>
    findByTaskIdOrderByAttemptNumberAsc(
            String taskId
    );
}
