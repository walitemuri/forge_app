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
    private final TaskScheduler taskScheduler;


    public TaskService(
            TaskRegistry taskRegistry,
            TaskScheduler taskScheduler) {

        this.taskRegistry = taskRegistry;
        this.taskScheduler = taskScheduler;
    }


    public ForgeTask submitTask(
            String command,
            List<String> arguments) {

        String taskId =
                UUID.randomUUID().toString();


        ForgeTask task =
                new ForgeTask(
                        taskId,
                        command,
                        arguments
                );


        taskRegistry.register(task);


        /*
         * Worker selection AND reservation now happen
         * atomically inside TaskScheduler.
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


        TaskAssignment assignment =
                TaskAssignment
                        .newBuilder()
                        .setTaskId(taskId)
                        .setCommand(command)
                        .addAllArguments(arguments)
                        .build();


        ControllerMessage message =
                ControllerMessage
                        .newBuilder()
                        .setTaskAssignment(
                                assignment
                        )
                        .build();


        boolean sent =
                worker.sendCommand(message);


        if (!sent) {

            /*
             * Scheduler already reserved the worker.
             * Undo that reservation if dispatch fails.
             */
            worker.releaseTask();

            throw new IllegalStateException(
                    "Failed to dispatch task to worker "
                            + worker.getWorkerId()
            );
        }


        task.setWorkerId(
                worker.getWorkerId()
        );

        task.setStatus(
                TaskStatus.DISPATCHED
        );
        
        taskRegistry.save(task);

        System.out.println();
        System.out.println(
                "=== TASK DISPATCHED ==="
        );

        System.out.println(
                "Task:   "
                        + taskId
        );

        System.out.println(
                "Worker: "
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