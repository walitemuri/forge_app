package dev.forge.controller.task;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;


@Component
public class TaskRecoveryService {

    private static final Set<TaskStatus>
            INTERRUPTED_STATUSES =
            EnumSet.of(
                    TaskStatus.CREATED,
                    TaskStatus.DISPATCHED,
                    TaskStatus.RUNNING
            );


    private final TaskRegistry taskRegistry;


    public TaskRecoveryService(
            TaskRegistry taskRegistry) {

        this.taskRegistry =
                taskRegistry;
    }


    public void recoverInterruptedTasks() {

        List<ForgeTask> interruptedTasks =
                taskRegistry.getByStatuses(
                        INTERRUPTED_STATUSES
                );


        if (interruptedTasks.isEmpty()) {

            System.out.println(
                    "Task recovery: no interrupted tasks found."
            );

            return;
        }


        System.out.println();
        System.out.println(
                "=== TASK RECOVERY ==="
        );


        for (ForgeTask task : interruptedTasks) {

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
                        + " interrupted task(s)."
        );

        System.out.println(
                "====================="
        );

        System.out.println();
    }
}