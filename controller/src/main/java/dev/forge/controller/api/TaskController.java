package dev.forge.controller.api;

import dev.forge.controller.task.ForgeTask;
import dev.forge.controller.task.TaskRegistry;
import dev.forge.controller.task.TaskService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import dev.forge.controller.task.TaskRegistry;

import java.util.List;


@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskRegistry taskRegistry;


    public TaskController(
        TaskService taskService,
        TaskRegistry taskRegistry) {

    this.taskService =
            taskService;

    this.taskRegistry =
            taskRegistry;
    }

    @PostMapping
    public ResponseEntity<?> submitTask(
            @RequestBody CreateTaskRequest request) {

        if (request.command() == null
                || request.command().isBlank()) {

            return ResponseEntity
                    .badRequest()
                    .body(
                        "command must not be empty"
                    );
        }


        List<String> arguments =
                request.arguments() == null
                        ? List.of()
                        : request.arguments();


        try {

            ForgeTask task =
                    taskService.submitTask(
                            request.command(),
                            arguments
                    );


            return ResponseEntity
                    .accepted()
                    .body(
                        TaskResponse.from(task)
                    );
        }

        catch (IllegalStateException exception) {

            return ResponseEntity
                    .status(
                        HttpStatus.SERVICE_UNAVAILABLE
                    )
                    .body(
                        exception.getMessage()
                    );
        }
    }


    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(
            @PathVariable String taskId) {

        ForgeTask task =
                taskService.getTask(taskId);


        if (task == null) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        return ResponseEntity.ok(
                TaskResponse.from(task)
        );
    }
    @GetMapping
    public ResponseEntity<List<TaskResponse>> getTasks() {

        List<TaskResponse> tasks =
                taskRegistry
                        .getAll()
                        .stream()
                        .map(TaskResponse::from)
                        .toList();

        return ResponseEntity.ok(tasks);
    }
}
