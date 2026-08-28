package dev.forge.controller.task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class WorkerFailureService {

    private final TaskRegistry
            taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;


    public WorkerFailureService(
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry) {

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;
    }


    @Transactional
    public void handleWorkerLost(
            String workerId) {

        List<TaskAttempt> activeAttempts =
                taskAttemptRegistry
                        .getActiveForWorker(
                                workerId
                        );


        if (activeAttempts.isEmpty()) {

            System.out.println(
                    "No active attempts assigned to lost worker "
                            + workerId
            );

            return;
        }


        System.out.println();
        System.out.println(
                "=== WORKER TASK RECOVERY ==="
        );


        for (TaskAttempt attempt :
                activeAttempts) {

            String taskId =
                    attempt.getTaskId();


            /*
             * The physical attempt definitely belonged
             * to the lost worker.
             */
            System.out.println(
                    "Marking attempt LOST: "
                            + attempt.getId()
                            + " task="
                            + taskId
                            + " attemptNumber="
                            + attempt.getAttemptNumber()
            );


            attempt.markLost();

            taskAttemptRegistry.save(
                    attempt
            );


            /*
             * Only the newest attempt is allowed to
             * control logical task state.
             *
             * This keeps an older worker failure from
             * corrupting a newer retry.
             */
            TaskAttempt latestAttempt =
                    taskAttemptRegistry
                            .getLatestForTask(
                                    taskId
                            );


            if (latestAttempt == null
                    || !latestAttempt
                            .getId()
                            .equals(
                                    attempt.getId()
                            )) {

                System.out.println(
                        "Attempt is stale; logical task unchanged."
                );

                continue;
            }


            ForgeTask task =
                    taskRegistry.get(
                            taskId
                    );


            if (task == null) {

                continue;
            }


            if (task.getStatus()
                    == TaskStatus.CREATED
                    || task.getStatus()
                    == TaskStatus.DISPATCHED
                    || task.getStatus()
                    == TaskStatus.RUNNING) {

                task.markLost();

                taskRegistry.save(
                        task
                );


                System.out.println(
                        "Marking task LOST: "
                                + taskId
                );
            }
        }


        System.out.println(
                "============================"
        );

        System.out.println();
    }
}