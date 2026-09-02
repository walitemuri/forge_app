package dev.forge.controller.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class DependencyCoordinator {

    private final TaskRegistry taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;


    public DependencyCoordinator(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;
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


            List<String> dependencyIds =
                    task.getDependsOnTaskIds();


            /*
             * Defensive recovery.
             */
            if (dependencyIds.isEmpty()) {

                task.markPending();

                taskRegistry.save(
                        task
                );

                continue;
            }


            boolean allSucceeded =
                    true;

            ForgeTask failedDependency =
                    null;


            for (String dependencyId :
                    dependencyIds) {

                ForgeTask dependency =
                        taskRegistry.get(
                                dependencyId
                        );


                /*
                 * FK constraints should make this
                 * impossible.
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


                if (dependencyPreventsExecution(
                        dependency)) {

                    failedDependency =
                            dependency;

                    break;
                }
            }


            /*
             * One permanently unsuccessful parent
             * makes the entire child impossible.
             */
            if (failedDependency != null) {

                skipTask(
                        task,
                        failedDependency
                );

                continue;
            }


            /*
             * Fan-in condition:
             *
             * EVERY dependency must succeed.
             */
            if (allSucceeded) {

                task.markPending();

                taskRegistry.save(
                        task
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
         * These can never become successful
         * automatically.
         */
        if (dependency.getStatus()
                == TaskStatus.CANCELLED
                || dependency.getStatus()
                == TaskStatus.SKIPPED) {

            return true;
        }


        /*
         * FAILED and LOST can still have automatic
         * retries remaining.
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