package dev.forge.controller.event;

import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ExecutionEventService {

    private final ExecutionEventRepository repository;


    public ExecutionEventService(
            ExecutionEventRepository repository) {

        this.repository =
                repository;
    }


    public ExecutionEvent record(
            ExecutionEventType type,
            String workflowId,
            String taskId,
            String attemptId,
            String workerId,
            String message) {

        ExecutionEvent event =
                new ExecutionEvent(
                        type,
                        workflowId,
                        taskId,
                        attemptId,
                        workerId,
                        message
                );


        return repository.save(
                event
        );
    }


    public List<ExecutionEvent> getForTask(
            String taskId) {

        return repository
                .findByTaskIdOrderByIdAsc(
                        taskId
                );
    }


    public List<ExecutionEvent> getForWorkflow(
            String workflowId) {

        return repository
                .findByWorkflowIdOrderByIdAsc(
                        workflowId
                );
    }


    public List<ExecutionEvent> getForAttempt(
            String attemptId) {

        return repository
                .findByAttemptIdOrderByIdAsc(
                        attemptId
                );
    }
}
