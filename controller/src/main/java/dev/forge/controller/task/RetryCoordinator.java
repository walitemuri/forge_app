package dev.forge.controller.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;


@Component
public class RetryCoordinator {

    private static final Set<TaskStatus>
            RETRYABLE_STATUSES =
            EnumSet.of(
                    TaskStatus.LOST,
                    TaskStatus.FAILED
            );


    private final TaskRegistry
            taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;

    private final TaskService
            taskService;


    public RetryCoordinator(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            TaskService taskService) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;

        this.taskService =
                taskService;
    }


    @Scheduled(fixedDelay = 5000)
    public void retryEligibleTasks() {

        List<ForgeTask> tasks =
                taskRegistry.getByStatuses(
                        RETRYABLE_STATUSES
                );


        for (ForgeTask task : tasks) {

            TaskAttempt latestAttempt =
                    taskAttemptRegistry
                            .getLatestForTask(
                                    task.getId()
                            );


            if (latestAttempt == null) {
                continue;
            }


            if (latestAttempt.getAttemptNumber()
                    >= task.getMaxAttempts()) {

                continue;
            }


            taskService.retryAutomatically(
                    task.getId()
            );
        }
    }
}
