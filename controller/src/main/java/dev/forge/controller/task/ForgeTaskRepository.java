package dev.forge.controller.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;


public interface ForgeTaskRepository
        extends JpaRepository<ForgeTask, String> {

    List<ForgeTask> findByStatusIn(
            Collection<TaskStatus> statuses
    );


    List<ForgeTask> findByStatusOrderByCreatedAtAsc(
            TaskStatus status
    );


    List<ForgeTask> findByWorkflowIdOrderByCreatedAtAsc(
            String workflowId
    );
}