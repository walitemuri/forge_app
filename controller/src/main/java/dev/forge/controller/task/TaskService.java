package dev.forge.controller.task;

import dev.forge.controller.grpc.WorkerState;
import dev.forge.controller.scheduler.TaskScheduler;

import dev.forge.proto.ControllerMessage;
import dev.forge.proto.TaskAssignment;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskService {

    private final TaskRegistry taskRegistry;
    private final TaskScheduler scheduler;

    public TaskService(
            TaskRegistry taskRegistry,
            TaskScheduler scheduler) {

        this.taskRegistry = taskRegistry;
        this.scheduler = scheduler;
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


        Optional<WorkerState> selectedWorker =
                scheduler.selectWorker();


        if (selectedWorker.isEmpty()) {

            throw new IllegalStateException(
                    "No available Forge workers"
            );
        }


        WorkerState worker =
                selectedWorker.get();


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

                        .setTaskAssignment(assignment)

                        .build();


        boolean sent =
                worker.sendCommand(message);


        if (!sent) {

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


        System.out.println();
        System.out.println("=== TASK DISPATCHED ===");
        System.out.println("Task:   " + taskId);
        System.out.println("Worker: " + worker.getWorkerId());
        System.out.println("Command: " + command);
        System.out.println("=======================");
        System.out.println();


        return task;
    }


    public ForgeTask getTask(String taskId) {

        return taskRegistry.get(taskId);
    }
}