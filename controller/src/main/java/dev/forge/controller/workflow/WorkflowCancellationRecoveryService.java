package dev.forge.controller.workflow;

import dev.forge.controller.task.ForgeTask;
import dev.forge.controller.task.TaskRegistry;
import dev.forge.controller.task.TaskStatus;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Component
public class WorkflowCancellationRecoveryService {

    private final ForgeWorkflowRepository
            workflowRepository;

    private final TaskRegistry
            taskRegistry;


    public WorkflowCancellationRecoveryService(
            ForgeWorkflowRepository workflowRepository,
            TaskRegistry taskRegistry) {

        this.workflowRepository =
                workflowRepository;

        this.taskRegistry =
                taskRegistry;
    }


    @Transactional
    public void recoverCancelledWorkflows() {

        List<ForgeWorkflow> workflows =
                workflowRepository
                        .findAllByCancelRequestedTrue();


        if (workflows.isEmpty()) {

            System.out.println(
                    "Workflow cancellation recovery: "
                            + "no cancelled workflows found."
            );

            return;
        }


        System.out.println();
        System.out.println(
                "=== WORKFLOW CANCELLATION RECOVERY ==="
        );


        int recoveredTasks = 0;


        for (ForgeWorkflow workflow :
                workflows) {

            List<ForgeTask> tasks =
                    taskRegistry
                            .getByWorkflowId(
                                    workflow.getId()
                            );


            boolean changed =
                    false;


            for (ForgeTask task :
                    tasks) {

                TaskStatus status =
                        task.getStatus();


                /*
                 * Completed successful work remains
                 * successful.
                 *
                 * Already-terminal cancellation states
                 * need no further action.
                 */
                if (status == TaskStatus.SUCCEEDED
                        || status == TaskStatus.CANCELLED
                        || status == TaskStatus.SKIPPED) {

                    continue;
                }


                /*
                 * At startup there is no valid execution
                 * owned by the previous controller.
                 *
                 * TaskRecoveryService has already changed
                 * interrupted DISPATCHED/RUNNING work to
                 * LOST before this method is called.
                 */
                task.requestCancellation();

                task.markCancelled();

                changed =
                        true;

                recoveredTasks++;


                System.out.println(
                        "Recovering workflow-cancelled task: "
                                + task.getId()
                                + " workflow="
                                + workflow.getId()
                                + " previousStatus="
                                + status
                                + " -> CANCELLED"
                );
            }


            if (changed) {

                taskRegistry.saveAll(
                        tasks
                );
            }
        }


        System.out.println(
                "Recovered "
                        + recoveredTasks
                        + " workflow task(s)."
        );

        System.out.println(
                "======================================"
        );

        System.out.println();
    }
}
