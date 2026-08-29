package dev.forge.controller.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
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


    /*
     * Backoff:
     *
     * Attempt 1 failure -> wait 5 sec
     * Attempt 2 failure -> wait 10 sec
     * Attempt 3 failure -> wait 20 sec
     * ...
     */
    private static final long
            BASE_RETRY_DELAY_SECONDS = 5;


    /*
     * Prevent absurdly large delays if retry
     * policy grows later.
     */
    private static final long
            MAX_RETRY_DELAY_SECONDS = 300;


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


    @Scheduled(fixedDelay = 1000)
    public void retryEligibleTasks() {

        List<ForgeTask> tasks =
                taskRegistry.getByStatuses(
                        RETRYABLE_STATUSES
                );


        for (ForgeTask task : tasks) {
            if (task.isCancelRequested()) {    
                    continue;
            }

            TaskAttempt latestAttempt =
                    taskAttemptRegistry
                            .getLatestForTask(
                                    task.getId()
                            );


            if (latestAttempt == null) {
                continue;
            }


            /*
             * Retry budget exhausted.
             */
            if (latestAttempt.getAttemptNumber()
                    >= task.getMaxAttempts()) {

                continue;
            }


            /*
             * We need a known completion time before
             * calculating when the retry becomes eligible.
             */
            if (latestAttempt.getFinishedAt()
                    == null) {

                continue;
            }


            long retryDelaySeconds =
                    calculateRetryDelaySeconds(
                            latestAttempt
                                    .getAttemptNumber()
                    );


            Instant retryAt =
                    latestAttempt
                            .getFinishedAt()
                            .plusSeconds(
                                    retryDelaySeconds
                            );


            if (Instant.now()
                    .isBefore(
                            retryAt
                    )) {

                continue;
            }


            System.out.println(
                    "Retry eligible: task="
                            + task.getId()
                            + " previousAttempt="
                            + latestAttempt
                                    .getAttemptNumber()
                            + " delay="
                            + retryDelaySeconds
                            + "s"
            );


            taskService.retryAutomatically(
                    task.getId()
            );
        }
    }


    private long calculateRetryDelaySeconds(
            int failedAttemptNumber) {

        /*
         * Attempt 1 -> 5
         * Attempt 2 -> 10
         * Attempt 3 -> 20
         *
         * delay = base * 2^(attemptNumber - 1)
         */
        long multiplier =
                1L << Math.min(
                        failedAttemptNumber - 1,
                        20
                );


        return Math.min(
                BASE_RETRY_DELAY_SECONDS
                        * multiplier,
                MAX_RETRY_DELAY_SECONDS
        );
    }
}