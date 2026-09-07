package dev.forge.controller.task;

import dev.forge.controller.event.ExecutionEventService;
import dev.forge.controller.event.ExecutionEventType;
import dev.forge.controller.grpc.WorkerSessionRecoveryCoordinator;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
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

    private final WorkerSessionRecoveryCoordinator
            workerSessionRecoveryCoordinator;


    public TaskRecoveryService(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            ExecutionEventService executionEventService,
            WorkerSessionRecoveryCoordinator
                    workerSessionRecoveryCoordinator) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;

        this.executionEventService =
                executionEventService;

        this.workerSessionRecoveryCoordinator =
                workerSessionRecoveryCoordinator;
    }


    @Transactional
    public void recoverInterruptedTasks() {

        // =====================================================
        // CREATED -> PENDING recovery
        // =====================================================

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


        // =====================================================
        // Find interrupted physical executions
        // =====================================================

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


        Instant startupTime =
                Instant.now();


        Set<String> deferredAttemptIds =
                new HashSet<>();


        List<TaskAttempt> attemptsToLose =
                new ArrayList<>();


        // =====================================================
        // Decide which attempts must be recovered immediately
        // =====================================================

        for (TaskAttempt attempt :
                interruptedAttempts) {

            boolean hasPersistedGrace =
                    attempt.getWorkerId() != null
                            && attempt.getWorkerSessionId() != null
                            && workerSessionRecoveryCoordinator
                                    .hasPendingRecovery(
                                            attempt.getWorkerId(),
                                            attempt.getWorkerSessionId(),
                                            startupTime
                                    );


            if (hasPersistedGrace) {

                deferredAttemptIds.add(
                        attempt.getId()
                );


                System.out.println(
                        "Deferring startup recovery for attempt "
                                + attempt.getId()
                                + " worker="
                                + attempt.getWorkerId()
                                + " session="
                                + attempt.getWorkerSessionId()
                                + " because durable replay grace is active"
                );

                continue;
            }


            System.out.println(
                    "Marking attempt LOST during controller recovery: "
                            + attempt.getId()
                            + " task="
                            + attempt.getTaskId()
                            + " attemptNumber="
                            + attempt.getAttemptNumber()
                            + " previousStatus="
                            + attempt.getStatus()
                            + " worker="
                            + attempt.getWorkerId()
                            + " session="
                            + attempt.getWorkerSessionId()
            );


            attempt.markLost();

            attemptsToLose.add(
                    attempt
            );
        }


        if (!attemptsToLose.isEmpty()) {

            taskAttemptRegistry.saveAll(
                    attemptsToLose
            );


            for (TaskAttempt attempt :
                    attemptsToLose) {

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
        }


        // =====================================================
        // Logical task recovery
        //
        // A task whose CURRENT physical attempt has an active
        // durable replay grace must remain in-flight.
        // =====================================================

        List<ForgeTask> tasksToLose =
                new ArrayList<>();


        for (ForgeTask task :
                interruptedTasks) {

            TaskAttempt latestAttempt =
                    taskAttemptRegistry
                            .getLatestForTask(
                                    task.getId()
                            );


            if (latestAttempt != null
                    && deferredAttemptIds.contains(
                            latestAttempt.getId()
                    )) {

                System.out.println(
                        "Deferring startup task recovery: "
                                + task.getId()
                                + " attempt="
                                + latestAttempt.getId()
                );

                continue;
            }


            System.out.println(
                    "Marking task LOST during controller recovery: "
                            + task.getId()
                            + " previousStatus="
                            + task.getStatus()
                            + " worker="
                            + task.getWorkerId()
            );


            task.markLost();

            tasksToLose.add(
                    task
            );
        }


        if (!tasksToLose.isEmpty()) {

            taskRegistry.saveAll(
                    tasksToLose
            );


            for (ForgeTask task :
                    tasksToLose) {

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
        }


        System.out.println();
        System.out.println(
                "=== TASK RECOVERY ==="
        );

        System.out.println(
                "Immediate LOST attempts: "
                        + attemptsToLose.size()
        );

        System.out.println(
                "Deferred by durable session grace: "
                        + deferredAttemptIds.size()
        );

        System.out.println(
                "Immediate LOST tasks: "
                        + tasksToLose.size()
        );

        System.out.println(
                "====================="
        );

        System.out.println();
    }
}
