package dev.forge.controller.task;

import dev.forge.controller.event.ExecutionEventService;
import dev.forge.controller.event.ExecutionEventType;
import dev.forge.controller.workflow.WorkflowExecutionGuard;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class DependencyCoordinator {

    private final TaskRegistry taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;

    private final WorkflowExecutionGuard
            workflowExecutionGuard;

    private final ExecutionEventService
            executionEventService;


    public DependencyCoordinator(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            WorkflowExecutionGuard workflowExecutionGuard,
            ExecutionEventService executionEventService) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;

        this.workflowExecutionGuard =
                workflowExecutionGuard;

        this.executionEventService =
                executionEventService;
    }


    @Scheduled(fixedDelay = 500)
    public void releaseSatisfiedDependencies() {

        List<ForgeTask> blockedTasks =
                taskRegistry
                        .getByStatusOrdered(
                                TaskStatus.BLOCKED
                        );


        for (ForgeTask task :
                blockedTasks) {

            if (task.isCancelRequested()) {

                continue;
            }


            // =====================================================
            // Workflow cancellation
            // =====================================================

            if (workflowExecutionGuard
                    .isCancellationRequested(
                            task
                    )) {

                task.requestCancellation();

                task.markCancelled();


                taskRegistry.save(
                        task
                );


                executionEventService.record(
                        ExecutionEventType.TASK_CANCELLED,
                        task.getWorkflowId(),
                        task.getId(),
                        null,
                        null,
                        "Blocked task cancelled because workflow cancellation was requested"
                );


                System.out.println(
                        "■ WORKFLOW-CANCELLED BLOCKED TASK: "
                                + task.getId()
                );


                continue;
            }


            List<String> dependencyIds =
                    task.getDependsOnTaskIds();


            // =====================================================
            // Defensive recovery
            // =====================================================

            /*
             * A BLOCKED task with no dependencies should not
             * normally exist.
             *
             * If one does appear after migration/recovery,
             * restore it to the runnable PENDING state.
             */
            if (dependencyIds.isEmpty()) {

                task.markPending();


                taskRegistry.save(
                        task
                );


                executionEventService.record(
                        ExecutionEventType.TASK_PENDING,
                        task.getWorkflowId(),
                        task.getId(),
                        null,
                        null,
                        "Blocked task had no dependencies and was restored to PENDING"
                );


                continue;
            }


            boolean allSucceeded =
                    true;

            ForgeTask failedDependency =
                    null;


            // =====================================================
            // Evaluate every parent
            // =====================================================

            for (String dependencyId :
                    dependencyIds) {

                ForgeTask dependency =
                        taskRegistry.get(
                                dependencyId
                        );


                /*
                 * FK constraints should make a missing
                 * dependency impossible.
                 */
                if (dependency == null) {

                    allSucceeded =
                            false;

                    continue;
                }


                if (dependency.getStatus()
                        == TaskStatus.SUCCEEDED) {

                    continue;
                }


                allSucceeded =
                        false;


                /*
                 * If this dependency can never become
                 * successful, the child can never execute.
                 */
                if (dependencyPreventsExecution(
                        dependency)) {

                    failedDependency =
                            dependency;

                    break;
                }
            }


            // =====================================================
            // Permanently failed dependency
            // =====================================================

            if (failedDependency != null) {

                skipTask(
                        task,
                        failedDependency
                );


                continue;
            }


            // =====================================================
            // Every dependency succeeded
            // =====================================================

            if (allSucceeded) {

                task.markPending();


                taskRegistry.save(
                        task
                );


                executionEventService.record(
                        ExecutionEventType.TASK_PENDING,
                        task.getWorkflowId(),
                        task.getId(),
                        null,
                        null,
                        "All dependencies succeeded; task released to PENDING"
                );


                System.out.println(
                        "✓ ALL DEPENDENCIES SATISFIED: task="
                                + task.getId()
                                + " dependencies="
                                + dependencyIds
                                + " → PENDING"
                );
            }
        }
    }


    private boolean dependencyPreventsExecution(
            ForgeTask dependency) {

        /*
         * These states can never become successful
         * automatically.
         */
        if (dependency.getStatus()
                == TaskStatus.CANCELLED
                || dependency.getStatus()
                == TaskStatus.SKIPPED) {

            return true;
        }


        /*
         * FAILED and LOST can still become successful
         * through automatic retries.
         */
        if (dependency.getStatus()
                == TaskStatus.FAILED
                || dependency.getStatus()
                == TaskStatus.LOST) {

            TaskAttempt latestAttempt =
                    taskAttemptRegistry
                            .getLatestForTask(
                                    dependency.getId()
                            );


            if (latestAttempt == null) {

                return false;
            }


            /*
             * Once the retry budget is exhausted,
             * this dependency is permanently unsuccessful.
             */
            return latestAttempt
                    .getAttemptNumber()
                    >= dependency
                    .getMaxAttempts();
        }


        return false;
    }


    private void skipTask(
            ForgeTask task,
            ForgeTask failedDependency) {

        task.markSkipped();


        taskRegistry.save(
                task
        );


        executionEventService.record(
                ExecutionEventType.TASK_SKIPPED,
                task.getWorkflowId(),
                task.getId(),
                null,
                null,
                "Task skipped because dependency "
                        + failedDependency.getId()
                        + " ended in "
                        + failedDependency.getStatus()
        );


        System.out.println(
                "⊘ DEPENDENCY FAILED: task="
                        + task.getId()
                        + " dependency="
                        + failedDependency.getId()
                        + " dependencyStatus="
                        + failedDependency.getStatus()
                        + " → SKIPPED"
        );
    }
}
