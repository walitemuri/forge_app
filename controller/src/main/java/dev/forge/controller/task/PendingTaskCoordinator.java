package dev.forge.controller.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class PendingTaskCoordinator {

    private final TaskRegistry
            taskRegistry;

    private final TaskService
            taskService;


    public PendingTaskCoordinator(
            TaskRegistry taskRegistry,
            TaskService taskService) {

        this.taskRegistry =
                taskRegistry;

        this.taskService =
                taskService;
    }


    @Scheduled(fixedDelay = 500)
    public void dispatchPendingTasks() {

        List<ForgeTask> pendingTasks =
                taskRegistry
                        .getByStatusOrdered(
                                TaskStatus.PENDING
                        );


        for (ForgeTask task :
                pendingTasks) {

            boolean dispatched =
                    taskService
                            .tryDispatchPendingTask(
                                    task.getId()
                            );


            /*
             * If nothing has capacity, don't hammer
             * the scheduler once for every queued job.
             */
            if (!dispatched) {

                break;
            }
        }
    }
}
