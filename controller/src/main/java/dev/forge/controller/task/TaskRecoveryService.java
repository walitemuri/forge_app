package dev.forge.controller.task;

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
                    TaskStatus.CREATED,
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


    private final TaskRegistry taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;


    public TaskRecoveryService(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;
    }


    @Transactional
    public void recoverInterruptedTasks() {

        List<TaskAttempt> interruptedAttempts =
                taskAttemptRegistry.getByStatuses(
                        INTERRUPTED_ATTEMPT_STATUSES
                );


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


        /*
         * First close the physical executions.
         */
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
         * Then mark the logical tasks interrupted.
         */
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