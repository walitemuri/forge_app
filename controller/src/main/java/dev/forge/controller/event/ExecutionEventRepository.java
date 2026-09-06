package dev.forge.controller.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface ExecutionEventRepository
        extends JpaRepository<ExecutionEvent, Long> {


    List<ExecutionEvent>
    findByTaskIdOrderByIdAsc(
            String taskId
    );


    List<ExecutionEvent>
    findByWorkflowIdOrderByIdAsc(
            String workflowId
    );


    List<ExecutionEvent>
    findByAttemptIdOrderByIdAsc(
            String attemptId
    );


    boolean existsByEventTypeAndTaskIdAndAttemptId(
            ExecutionEventType eventType,
            String taskId,
            String attemptId
    );
}
