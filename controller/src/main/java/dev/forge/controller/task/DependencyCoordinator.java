package dev.forge.controller.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DependencyCoordinator {

    private final TaskRegistry taskRegistry;
    private final TaskAttemptRegistry taskAttemptRegistry;


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


        for (ForgeTask task : blockedTasks) {

            if (task.isCancelRequested()) {
                continue;
            }


            String dependencyId =
                    task.getDependsOnTaskId();


            if (dependencyId == null) {

                task.markPending();
                taskRegistry.save(task);

                continue;
            }


            ForgeTask dependency =
                    taskRegistry.get(
                            dependencyId
                    );


            if (dependency == null) {
                continue;
            }


            /*
             * Dependency succeeded:
             * release child into normal pending queue.
             */
            if (dependency.getStatus()
                    == TaskStatus.SUCCEEDED) {

                task.markPending();
                taskRegistry.save(task);

                System.out.println(
                        "✓ DEPENDENCY SATISFIED: task="
                                + task.getId()
                                + " dependency="
                                + dependencyId
                                + " → PENDING"
                );

                continue;
            }


            /*
             * Cancellation is immediately terminal.
             */
            if (dependency.getStatus()
                    == TaskStatus.CANCELLED) {

                skipTask(
                        task,
                        dependency
                );

                continue;
            }


            /*
             * FAILED / LOST may still be retried.
             *
             * Do not skip the child until the dependency
             * has exhausted its retry budget.
             */
            if (dependency.getStatus()
                    == TaskStatus.FAILED
                    || dependency.getStatus()
                    == TaskStatus.LOST) {

                TaskAttempt latestAttempt =
                        taskAttemptRegistry
                                .getLatestForTask(
                                        dependencyId
                                );


                if (latestAttempt == null) {
                    continue;
                }


                boolean retriesExhausted =
                        latestAttempt
                                .getAttemptNumber()
                                >= dependency
                                .getMaxAttempts();


                if (retriesExhausted) {

                    skipTask(
                            task,
                            dependency
                    );
                }
            }
        }
    }


    private void skipTask(
            ForgeTask task,
            ForgeTask dependency) {

        task.markSkipped();

        taskRegistry.save(
                task
        );


        System.out.println(
                "⊘ DEPENDENCY FAILED: task="
                        + task.getId()
                        + " dependency="
                        + dependency.getId()
                        + " dependencyStatus="
                        + dependency.getStatus()
                        + " → SKIPPED"
        );
    }
}