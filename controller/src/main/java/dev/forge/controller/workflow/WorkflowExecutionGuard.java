package dev.forge.controller.workflow;

import dev.forge.controller.task.ForgeTask;

import org.springframework.stereotype.Service;


@Service
public class WorkflowExecutionGuard {

    private final ForgeWorkflowRepository
            workflowRepository;


    public WorkflowExecutionGuard(
            ForgeWorkflowRepository workflowRepository) {

        this.workflowRepository =
                workflowRepository;
    }


    public boolean isCancellationRequested(
            ForgeTask task) {

        String workflowId =
                task.getWorkflowId();


        /*
         * Standalone tasks do not belong to
         * workflows.
         */
        if (workflowId == null) {

            return false;
        }


        return workflowRepository
                .existsByIdAndCancelRequestedTrue(
                        workflowId
                );
    }
}
