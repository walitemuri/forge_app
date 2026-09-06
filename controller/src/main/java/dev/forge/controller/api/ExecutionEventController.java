package dev.forge.controller.api;

import dev.forge.controller.event.ExecutionEventService;

import dev.forge.controller.task.TaskAttemptRegistry;
import dev.forge.controller.task.TaskRegistry;

import dev.forge.controller.workflow.ForgeWorkflowRepository;

import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
public class ExecutionEventController {

    private final ExecutionEventService eventService;

    private final TaskRegistry taskRegistry;

    private final TaskAttemptRegistry taskAttemptRegistry;

    private final ForgeWorkflowRepository workflowRepository;


    public ExecutionEventController(
            ExecutionEventService eventService,
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            ForgeWorkflowRepository workflowRepository) {

        this.eventService =
                eventService;

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;

        this.workflowRepository =
                workflowRepository;
    }


    @GetMapping(
            "/api/tasks/{taskId}/events"
    )
    public ResponseEntity<
            List<ExecutionEventResponse>>
    getTaskEvents(
            @PathVariable String taskId) {

        if (taskRegistry.get(taskId) == null) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        List<ExecutionEventResponse> events =
                eventService
                        .getForTask(taskId)
                        .stream()
                        .map(
                                ExecutionEventResponse::from
                        )
                        .toList();


        return ResponseEntity.ok(
                events
        );
    }


    @GetMapping(
            "/api/workflows/{workflowId}/events"
    )
    public ResponseEntity<
            List<ExecutionEventResponse>>
    getWorkflowEvents(
            @PathVariable String workflowId) {

        if (!workflowRepository
                .existsById(
                        workflowId
                )) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        List<ExecutionEventResponse> events =
                eventService
                        .getForWorkflow(
                                workflowId
                        )
                        .stream()
                        .map(
                                ExecutionEventResponse::from
                        )
                        .toList();


        return ResponseEntity.ok(
                events
        );
    }


    @GetMapping(
            "/api/attempts/{attemptId}/events"
    )
    public ResponseEntity<
            List<ExecutionEventResponse>>
    getAttemptEvents(
            @PathVariable String attemptId) {

        if (taskAttemptRegistry
                .get(attemptId) == null) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        List<ExecutionEventResponse> events =
                eventService
                        .getForAttempt(
                                attemptId
                        )
                        .stream()
                        .map(
                                ExecutionEventResponse::from
                        )
                        .toList();


        return ResponseEntity.ok(
                events
        );
    }
}
