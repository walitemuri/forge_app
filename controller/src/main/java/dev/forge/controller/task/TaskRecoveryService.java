package dev.forge.controller.task;

import dev.forge.controller.event.ExecutionEventService;
import dev.forge.controller.event.ExecutionEventType;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;


@Component
public class TaskRecoveryService {

    private static final Set<TaskStatus>
            INTERRUPTED_TASK_STATUSES =
            EnumSet.of(
                    TaskStatus.DISPATCHED,
                    TaskStatus.RUNNING
            );


    private static final Set<TaskAttemptStatus>
            INTERRUPTED_ATTEMPT_STATUSES =
            EnumSet.of(
                    TaskAttemptStatus.CREATED,
                    TaskAttemptStatus.DISPATCHED,
                    TaskAttemptStatus.RUNNING
            );


    private final TaskRegistry
            taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;

    private final ExecutionEventService
            executionEventService;


    public TaskRecoveryService(
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
    public void recoverInterruptedTasks() {

        // =========================================================
        // CREATED → PENDING recovery
        // =========================================================

        List<ForgeTask> createdTasks =
                taskRegistry.getByStatuses(
                        EnumSet.of(
                                TaskStatus.CREATED
                        )
                );


        for (ForgeTask task :
                createdTasks) {

            System.out.println(
                    "Recovering CREATED task as PENDING: "
                            + task.getId()
            );


            task.markPending();
        }


        if (!createdTasks.isEmpty()) {

            taskRegistry.saveAll(
                    createdTasks
            );


            for (ForgeTask task :
                    createdTasks) {

                executionEventService.record(
                        ExecutionEventType.TASK_PENDING,
                        task.getWorkflowId(),
                        task.getId(),
                        null,
                        null,
                        "Controller recovery restored CREATED task to PENDING"
                );
            }
        }


        // =========================================================
        // Find interrupted physical executions
        // =========================================================

        List<TaskAttempt> interruptedAttempts =
                taskAttemptRegistry.getByStatuses(
                        INTERRUPTED_ATTEMPT_STATUSES
                );


        // =========================================================
        // Find interrupted logical tasks
        // =========================================================

        List<ForgeTask> interruptedTasks =
                taskRegistry.getByStatuses(
                        INTERRUPTED_TASK_STATUSES
                );


        if (interruptedAttempts.isEmpty()
                && interruptedTasks.isEmpty()) {

            System.out.println(
                    "Task recovery: no interrupted tasks found."
            );

            return;
        }


        System.out.println();
        System.out.println(
                "=== TASK RECOVERY ==="
        );


        // =========================================================
        // Physical attempts → LOST
        // =========================================================

        for (TaskAttempt attempt :
                interruptedAttempts) {

            System.out.println(
                    "Marking attempt LOST: "
                            + attempt.getId()
                            + " task="
                            + attempt.getTaskId()
                            + " attemptNumber="
                            + attempt.getAttemptNumber()
                            + " previousStatus="
                            + attempt.getStatus()
                            + " worker="
                            + attempt.getWorkerId()
            );


            attempt.markLost();
        }


        taskAttemptRegistry.saveAll(
                interruptedAttempts
        );


        /*
         * Record only AFTER the attempt state has
         * been persisted.
         */
        for (TaskAttempt attempt :
                interruptedAttempts) {

            ForgeTask task =
                    taskRegistry.get(
                            attempt.getTaskId()
                    );


            if (task == null) {

                continue;
            }


            executionEventService.record(
                    ExecutionEventType.ATTEMPT_LOST,
                    task.getWorkflowId(),
                    task.getId(),
                    attempt.getId(),
                    attempt.getWorkerId(),
                    "Controller restarted while execution attempt was active"
            );
        }


        // =========================================================
        // Logical tasks → LOST
        // =========================================================

        for (ForgeTask task :
                interruptedTasks) {

            System.out.println(
                    "Marking task LOST: "
                            + task.getId()
                            + " previousStatus="
                            + task.getStatus()
                            + " worker="
                            + task.getWorkerId()
            );


            task.markLost();
        }


        taskRegistry.saveAll(
                interruptedTasks
        );


        /*
         * Again, persist the task state before writing
         * the corresponding timeline event.
         */
        for (ForgeTask task :
                interruptedTasks) {

            TaskAttempt latestAttempt =
                    taskAttemptRegistry
                            .getLatestForTask(
                                    task.getId()
                            );


            executionEventService.record(
                    ExecutionEventType.TASK_LOST,
                    task.getWorkflowId(),
                    task.getId(),
                    latestAttempt == null
                            ? null
                            : latestAttempt.getId(),
                    task.getWorkerId(),
                    "Controller restarted while task was in flight"
            );
        }


        System.out.println(
                "Recovered "
                        + interruptedTasks.size()
                        + " task(s) and "
                        + interruptedAttempts.size()
                        + " attempt(s)."
        );


        System.out.println(
                "====================="
        );

        System.out.println();
    }
}
