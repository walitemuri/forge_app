package dev.forge.controller.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class DependencyCoordinator {

    private final TaskRegistry
            taskRegistry;


    public DependencyCoordinator(
            TaskRegistry taskRegistry) {

        this.taskRegistry =
                taskRegistry;
    }


    @Scheduled(fixedDelay = 500)
    public void releaseSatisfiedDependencies() {
        System.out.println(
                 "[dependency-coordinator] tick"
        );

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


            String dependencyId =
                    task.getDependsOnTaskId();


            /*
             * Defensive recovery:
             * BLOCKED should always have a dependency.
             */
            if (dependencyId == null) {

                task.markPending();

                taskRegistry.save(
                        task
                );

                continue;
            }


            ForgeTask dependency =
                    taskRegistry.get(
                            dependencyId
                    );


            /*
             * The foreign key should make this impossible,
             * but don't crash the scheduler if database
             * state is ever inconsistent.
             */
            if (dependency == null) {

                continue;
            }


            if (dependency.getStatus()
                    != TaskStatus.SUCCEEDED) {

                continue;
            }


            task.markPending();


            taskRegistry.save(
                    task
            );


            System.out.println(
                    "✓ DEPENDENCY SATISFIED: task="
                            + task.getId()
                            + " dependency="
                            + dependencyId
                            + " → PENDING"
            );
        }
    }
}
