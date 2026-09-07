package dev.forge.controller.task;

import dev.forge.controller.event.ExecutionEventService;
import dev.forge.controller.event.ExecutionEventType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class WorkerFailureService {

    private final TaskRegistry
            taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;

    private final ExecutionEventService
            executionEventService;


    public WorkerFailureService(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            ExecutionEventService executionEventService) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;

        this.executionEventService =
                executionEventService;
    }


    @Transactional
    public void handleWorkerLost(
            String workerId) {

        executionEventService.record(
                ExecutionEventType.WORKER_LOST,
                null,
                null,
                null,
                workerId,
                "Worker became unreachable"
        );


        recoverAttempts(
                taskAttemptRegistry
                        .getActiveForWorker(
                                workerId
                        ),
                workerId
        );
    }


    @Transactional
    public void handleWorkerSessionLost(
            String workerId,
            String workerSessionId) {

        executionEventService.record(
                ExecutionEventType.WORKER_LOST,
                null,
                null,
                null,
                workerId,
                "Worker session "
                        + workerSessionId
                        + " became unreachable"
        );


        recoverAttempts(
                taskAttemptRegistry
                        .getActiveForWorkerSession(
                                workerId,
                                workerSessionId
                        ),
                workerId
                        + "/"
                        + workerSessionId
        );
    }


    private void recoverAttempts(
            List<TaskAttempt> activeAttempts,
            String ownerDescription) {

        if (activeAttempts.isEmpty()) {

            System.out.println(
                    "No active attempts assigned to "
                            + ownerDescription
            );

            return;
        }


        System.out.println();
        System.out.println(
                "=== WORKER TASK RECOVERY ==="
        );


        for (TaskAttempt attempt :
                activeAttempts) {

            String taskId =
                    attempt.getTaskId();


            ForgeTask task =
                    taskRegistry.get(
                            taskId
                    );


            System.out.println(
                    "Marking attempt LOST: "
                            + attempt.getId()
                            + " task="
                            + taskId
                            + " attemptNumber="
                            + attempt.getAttemptNumber()
            );


            attempt.markLost();

            taskAttemptRegistry.save(
                    attempt
            );


            executionEventService.record(
                    ExecutionEventType.ATTEMPT_LOST,
                    task == null
                            ? null
                            : task.getWorkflowId(),
                    taskId,
                    attempt.getId(),
                    attempt.getWorkerId(),
                    "Execution attempt lost with "
                            + ownerDescription
            );


            /*
             * Only the latest physical attempt may
             * control the logical task.
             */
            TaskAttempt latestAttempt =
                    taskAttemptRegistry
                            .getLatestForTask(
                                    taskId
                            );


            if (latestAttempt == null
                    || !latestAttempt
                            .getId()
                            .equals(
                                    attempt.getId()
                            )) {

                continue;
            }


            if (task == null) {

                continue;
            }


            if (task.getStatus()
                    == TaskStatus.CREATED
                    || task.getStatus()
                    == TaskStatus.DISPATCHED
                    || task.getStatus()
                    == TaskStatus.RUNNING) {

                task.markLost();

                taskRegistry.save(
                        task
                );


                executionEventService.record(
                        ExecutionEventType.TASK_LOST,
                        task.getWorkflowId(),
                        task.getId(),
                        attempt.getId(),
                        attempt.getWorkerId(),
                        "Task lost with "
                                + ownerDescription
                );
            }
        }


        System.out.println(
                "============================"
        );

        System.out.println();
    }
}
