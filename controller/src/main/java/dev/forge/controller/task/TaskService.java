package dev.forge.controller.task;

import dev.forge.controller.grpc.WorkerState;
import dev.forge.controller.scheduler.TaskScheduler;
import dev.forge.proto.ControllerMessage;
import dev.forge.proto.TaskAssignment;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;


@Service
public class TaskService {

    private final TaskRegistry taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;

    private final TaskScheduler taskScheduler;


    public TaskService(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            TaskScheduler taskScheduler) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;

        this.taskScheduler =
                taskScheduler;
    }


    public ForgeTask submitTask(
            String command,
            List<String> arguments) {

        String taskId =
                UUID.randomUUID().toString();


        ForgeTask task =
                taskRegistry.register(
                        new ForgeTask(
                                taskId,
                                command,
                                arguments
                        )
                );


        /*
         * For now every logical task begins with
         * exactly one physical attempt.
         */
        String attemptId =
                UUID.randomUUID().toString();


        TaskAttempt attempt =
                new TaskAttempt(
                        attemptId,
                        taskId,
                        1
                );


        attempt =
                taskAttemptRegistry.save(
                        attempt
                );


        /*
         * Choose worker and reserve capacity.
         */
        WorkerState worker =
                taskScheduler
                        .selectAndReserveWorker()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No available Forge workers"
                                        )
                        );


        /*
         * Persist ownership BEFORE gRPC send.
         *
         * This avoids the race we encountered earlier
         * where the worker could reply before PostgreSQL
         * knew which worker owned the execution.
         */
        task.setWorkerId(
                worker.getWorkerId()
        );

        task.setStatus(
                TaskStatus.DISPATCHED
        );

        task =
                taskRegistry.save(
                        task
                );


        attempt.markDispatched(
                worker.getWorkerId()
        );

        attempt =
                taskAttemptRegistry.save(
                        attempt
                );


        TaskAssignment assignment =
                TaskAssignment
                        .newBuilder()
                        .setTaskId(
                                taskId
                        )
                        .setAttemptId(
                                attemptId
                        )
                        .setCommand(
                                command
                        )
                        .addAllArguments(
                                arguments
                        )
                        .build();


        ControllerMessage message =
                ControllerMessage
                        .newBuilder()
                        .setTaskAssignment(
                                assignment
                        )
                        .build();


        boolean sent =
                worker.sendCommand(
                        message
                );


        if (!sent) {

            worker.releaseTask();


            attempt.failBeforeStart(
                    "Failed to dispatch task to worker "
                            + worker.getWorkerId()
            );

            taskAttemptRegistry.save(
                    attempt
            );


            task.setStatus(
                    TaskStatus.FAILED
            );

            taskRegistry.save(
                    task
            );


            throw new IllegalStateException(
                    "Failed to dispatch task to worker "
                            + worker.getWorkerId()
            );
        }


        System.out.println();
        System.out.println(
                "=== TASK DISPATCHED ==="
        );

        System.out.println(
                "Task:    "
                        + taskId
        );

        System.out.println(
                "Attempt: "
                        + attemptId
        );

        System.out.println(
                "Worker:  "
                        + worker.getWorkerId()
        );

        System.out.println(
                "Command: "
                        + command
        );

        System.out.println(
                "Outstanding: "
                        + worker.getOutstandingTasks()
        );

        System.out.println(
                "Effective load: "
                        + worker.getEffectiveLoad()
        );

        System.out.println(
                "======================="
        );

        System.out.println();


        return task;
    }


    public ForgeTask getTask(
            String taskId) {

        return taskRegistry.get(
                taskId
        );
    }
}