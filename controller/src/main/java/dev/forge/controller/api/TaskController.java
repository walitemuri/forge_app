package dev.forge.controller.api;

import dev.forge.controller.task.ForgeTask;
import dev.forge.controller.task.TaskAttempt;
import dev.forge.controller.task.TaskAttemptRegistry;
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

import java.util.List;


@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    private final TaskRegistry taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;


    public TaskController(
            TaskService taskService,
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry) {

        this.taskService =
                taskService;

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;
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
        int maxAttempts =
        request.maxAttempts() == null
                ? 1
                : request.maxAttempts();


        if (maxAttempts < 1
                || maxAttempts > 10) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            "maxAttempts must be between 1 and 10"
                    );
        }

        int timeoutSeconds =
                request.timeoutSeconds() == null
                        ? 0
                        : request.timeoutSeconds();

        if (timeoutSeconds < 0
        || timeoutSeconds > 86400) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            "timeoutSeconds must be between 0 and 86400"
                    );
        }

        String dependsOnTaskId =
        request.dependsOnTaskId();


        if (dependsOnTaskId != null
                && dependsOnTaskId.isBlank()) {

        dependsOnTaskId =
                null;
        }
        try {

            ForgeTask task =
                    taskService.submitTask(
                        request.command(),
                        arguments,
                        maxAttempts,
                        timeoutSeconds,
                        dependsOnTaskId
                );



            return ResponseEntity
                    .accepted()
                    .body(
                            TaskResponse.from(task)
                    );
        }
        catch (IllegalArgumentException exception) {

                return ResponseEntity
                        .badRequest()
                        .body(
                                exception.getMessage()
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


    @PostMapping("/{taskId}/retry")
    public ResponseEntity<?> retryTask(
            @PathVariable String taskId) {

        try {

            ForgeTask task =
                    taskService.retryTask(
                            taskId
                    );


            if (task == null) {

                return ResponseEntity
                        .notFound()
                        .build();
            }


            return ResponseEntity
                    .accepted()
                    .body(
                            TaskResponse.from(task)
                    );
        }

        catch (IllegalArgumentException exception) {

            return ResponseEntity
                    .status(
                            HttpStatus.CONFLICT
                    )
                    .body(
                            exception.getMessage()
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
                taskService.getTask(
                        taskId
                );


        if (task == null) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        return ResponseEntity.ok(
                TaskResponse.from(task)
        );
    }


    @GetMapping("/{taskId}/attempts")
    public ResponseEntity<List<TaskAttempt>>
            getTaskAttempts(
                    @PathVariable String taskId) {

        ForgeTask task =
                taskService.getTask(
                        taskId
                );


        if (task == null) {

            return ResponseEntity
                    .notFound()
                    .build();
        }


        return ResponseEntity.ok(
                taskAttemptRegistry
                        .getForTask(
                                taskId
                        )
        );
    }


    @GetMapping
    public ResponseEntity<List<TaskResponse>>
            getTasks() {

        List<TaskResponse> tasks =
                taskRegistry
                        .getAll()
                        .stream()
                        .map(
                                TaskResponse::from
                        )
                        .toList();


        return ResponseEntity.ok(
                tasks
        );
    }
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(
                @PathVariable String taskId) {

        try {

                ForgeTask task =
                        taskService.cancelTask(
                                taskId
                        );


                if (task == null) {

                return ResponseEntity
                        .notFound()
                        .build();
                }


                return ResponseEntity
                        .accepted()
                        .body(
                                TaskResponse.from(
                                        task
                                )
                        );
        }
        catch (IllegalArgumentException exception) {

                return ResponseEntity
                        .status(
                                HttpStatus.CONFLICT
                        )
                        .body(
                                exception.getMessage()
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
}
