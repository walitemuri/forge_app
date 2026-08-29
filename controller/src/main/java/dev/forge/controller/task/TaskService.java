package dev.forge.controller.task;

import dev.forge.controller.grpc.WorkerState;
import dev.forge.controller.scheduler.TaskScheduler;
import dev.forge.proto.ControllerMessage;
import dev.forge.proto.TaskAssignment;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import dev.forge.controller.grpc.WorkerRegistry;
import dev.forge.proto.CancelTask;

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
        List<String> arguments,
        int maxAttempts,
        int timeoutSeconds) {

        String taskId =
                UUID.randomUUID().toString();


        ForgeTask task =
                taskRegistry.register(
                       new ForgeTask(
                        taskId,
                        command,
                        arguments,
                        maxAttempts,
                        timeoutSeconds
                )
        );


        dispatchAttempt(
                task,
                1
        );


        /*
         * The worker may already have replied by now,
         * so return the latest database state.
         */
        return taskRegistry.get(
                taskId
        );
    }

    public synchronized boolean
        retryAutomatically(
                String taskId) {

        ForgeTask task =
                taskRegistry.get(
                        taskId
                );


        if (task == null) {
                return false;
        }
        if (task.isCancelRequested()) {

                return false;
        }


        if (task.getStatus() != TaskStatus.LOST
                && task.getStatus() != TaskStatus.FAILED) {

                return false;
        }


        TaskAttempt latestAttempt =
                taskAttemptRegistry
                        .getLatestForTask(
                                taskId
                        );


        if (latestAttempt == null) {
                return false;
        }


        /*
        * maxAttempts is the TOTAL number of executions,
        * not the number of retries.
        *
        * maxAttempts=3:
        * Attempt 1
        * Attempt 2
        * Attempt 3
        */
        if (latestAttempt.getAttemptNumber()
                >= task.getMaxAttempts()) {

                return false;
        }


        int nextAttemptNumber =
                latestAttempt
                        .getAttemptNumber()
                        + 1;


        try {

                dispatchAttempt(
                        task,
                        nextAttemptNumber
                );

                System.out.println(
                        "↻ AUTOMATIC RETRY: task="
                                + taskId
                                + " attempt="
                                + nextAttemptNumber
                                + "/"
                                + task.getMaxAttempts()
                );


                return true;
        }

        catch (IllegalStateException exception) {

                /*
                * Typically means there is no connected worker.
                * Leave the task LOST/FAILED. The retry
                * coordinator can try again later.
                */
                System.out.println(
                        "Automatic retry deferred: task="
                                + taskId
                                + " reason="
                                + exception.getMessage()
                );


        return false;
    }
}
    /*
     * Manual retry only.
     *
     * Automatic retries come later.
     */
    public synchronized ForgeTask retryTask(
            String taskId) {

        ForgeTask task =
                taskRegistry.get(
                        taskId
                );


        if (task == null) {
            return null;
        }
        if (task.isCancelRequested()) {

                throw new IllegalArgumentException(
                        "Task "
                                + taskId
                                + " cannot be retried because cancellation was requested"
                );
        }
        if (task.isCancelRequested()) {

        throw new IllegalArgumentException(
                "Task "
                        + taskId
                        + " cannot be retried because cancellation was requested"
        );
}

        if (task.getStatus() != TaskStatus.LOST
                && task.getStatus() != TaskStatus.FAILED) {

            throw new IllegalArgumentException(
                    "Task "
                            + taskId
                            + " cannot be retried from status "
                            + task.getStatus()
            );
        }


        TaskAttempt latestAttempt =
                taskAttemptRegistry
                        .getLatestForTask(
                                taskId
                        );


        if (latestAttempt == null) {

            throw new IllegalStateException(
                    "Task "
                            + taskId
                            + " has no execution attempts"
            );
        }


        if (latestAttempt.getStatus()
                != TaskAttemptStatus.LOST
                && latestAttempt.getStatus()
                != TaskAttemptStatus.FAILED) {

            throw new IllegalArgumentException(
                    "Latest attempt "
                            + latestAttempt.getId()
                            + " is not retryable from status "
                            + latestAttempt.getStatus()
            );
        }


        int nextAttemptNumber =
                latestAttempt
                        .getAttemptNumber()
                        + 1;


        dispatchAttempt(
                task,
                nextAttemptNumber
        );


        return taskRegistry.get(
                taskId
        );
    }


    private void dispatchAttempt(
            ForgeTask task,
            int attemptNumber) {

        /*
         * Reserve capacity before creating the attempt.
         *
         * If no worker exists, we don't create a useless
         * CREATED attempt.
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


        String attemptId =
                UUID.randomUUID().toString();


        TaskAttempt attempt =
                new TaskAttempt(
                        attemptId,
                        task.getId(),
                        attemptNumber
                );


        attempt =
                taskAttemptRegistry.save(
                        attempt
                );


        /*
         * Persist ownership before sending over gRPC.
         */
        task.markDispatched(
                worker.getWorkerId()
        );

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
                                task.getId()
                        )
                        .setAttemptId(
                                attemptId
                        )
                        .setCommand(
                                task.getCommand()
                        )
                        .addAllArguments(
                                task.getArguments()
                        )
                        .setTimeoutSeconds(
                                task.getTimeoutSeconds()
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
                    "Failed to dispatch attempt "
                            + attemptNumber
                            + " to worker "
                            + worker.getWorkerId()
            );
        }


        System.out.println();
        System.out.println(
                "=== TASK DISPATCHED ==="
        );

        System.out.println(
                "Task:    "
                        + task.getId()
        );

        System.out.println(
                "Attempt: "
                        + attemptId
        );

        System.out.println(
                "Attempt number: "
                        + attemptNumber
        );

        System.out.println(
                "Worker:  "
                        + worker.getWorkerId()
        );

        System.out.println(
                "Command: "
                        + task.getCommand()
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
    }

    public synchronized ForgeTask cancelTask(
                String taskId) {

        ForgeTask task =
                taskRegistry.get(
                        taskId
                );


        if (task == null) {

                return null;
        }


        /*
        * Repeated cancellation is idempotent.
        */
        if (task.getStatus()
                == TaskStatus.CANCELLED) {

                return task;
        }


        if (task.getStatus()
                != TaskStatus.DISPATCHED
                && task.getStatus()
                != TaskStatus.RUNNING) {

                throw new IllegalArgumentException(
                        "Task "
                                + taskId
                                + " cannot be cancelled from status "
                                + task.getStatus()
                );
        }


        if (task.isCancelRequested()) {

                return task;
        }


        TaskAttempt attempt =
                taskAttemptRegistry
                        .getLatestForTask(
                                taskId
                        );


        if (attempt == null) {

                throw new IllegalStateException(
                        "Task "
                                + taskId
                                + " has no execution attempt"
                );
        }


        if (attempt.getStatus()
                != TaskAttemptStatus.DISPATCHED
                && attempt.getStatus()
                != TaskAttemptStatus.RUNNING) {

                throw new IllegalArgumentException(
                        "Attempt "
                                + attempt.getId()
                                + " cannot be cancelled from status "
                                + attempt.getStatus()
                );
        }


        /*
        * Persist intent BEFORE sending the command.
        *
        * If the controller crashes immediately afterward,
        * we still remember that this task must not retry.
        */
        task.requestCancellation();

        taskRegistry.save(
                task
        );


        WorkerState worker =
                WorkerRegistry.get(
                        attempt.getWorkerId()
                );


        if (worker == null) {

                throw new IllegalStateException(
                        "Worker "
                                + attempt.getWorkerId()
                                + " is unavailable; cancellation request was recorded"
                );
        }


        CancelTask cancellation =
                CancelTask
                        .newBuilder()
                        .setTaskId(
                                taskId
                        )
                        .setAttemptId(
                                attempt.getId()
                        )
                        .build();


        ControllerMessage message =
                ControllerMessage
                        .newBuilder()
                        .setCancelTask(
                                cancellation
                        )
                        .build();


        boolean sent =
                worker.sendCommand(
                        message
                );


        if (!sent) {

                throw new IllegalStateException(
                        "Unable to deliver cancellation to worker "
                                + attempt.getWorkerId()
                                + "; cancellation request was recorded"
                );
        }


        System.out.println(
                "■ CANCELLATION REQUESTED: task="
                        + taskId
                        + " attempt="
                        + attempt.getId()
                        + " worker="
                        + attempt.getWorkerId()
        );


        return taskRegistry.get(
                taskId
        );
    }
    public ForgeTask getTask(
            String taskId) {

        return taskRegistry.get(
                taskId
        );
    }
}