package dev.forge.controller.workflow;

import dev.forge.controller.api.CreateWorkflowRequest;
import dev.forge.controller.api.WorkflowResponse;
import dev.forge.controller.api.WorkflowTaskRequest;
import dev.forge.controller.api.WorkflowTaskResponse;
import dev.forge.controller.api.WorkflowSummaryResponse;

import dev.forge.controller.task.ForgeTask;
import dev.forge.controller.task.TaskAttempt;
import dev.forge.controller.task.TaskAttemptRegistry;
import dev.forge.controller.task.TaskRegistry;
import dev.forge.controller.task.TaskService;
import dev.forge.controller.task.TaskStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


@Service
public class WorkflowService {

    private static final int MAX_WORKFLOW_TASKS =
            100;

    private static final int MAX_DEPENDENCIES_PER_TASK =
            100;


    private final ForgeWorkflowRepository
            workflowRepository;

    private final TaskRegistry
            taskRegistry;

    private final TaskAttemptRegistry
            taskAttemptRegistry;

    private final TaskService
            taskService;


    public WorkflowService(
            ForgeWorkflowRepository workflowRepository,
            TaskRegistry taskRegistry,
            TaskAttemptRegistry taskAttemptRegistry,
            TaskService taskService) {

        this.workflowRepository =
                workflowRepository;

        this.taskRegistry =
                taskRegistry;

        this.taskAttemptRegistry =
                taskAttemptRegistry;

        this.taskService =
                taskService;
    }


    @Transactional
    public WorkflowResponse createWorkflow(
            CreateWorkflowRequest request) {

        validateRequest(
                request
        );


        Map<String, NormalizedTask> tasksByKey =
                normalizeTasks(
                        request.tasks()
                );


        validateDependencies(
                tasksByKey
        );


        validateAcyclic(
                tasksByKey
        );


        String workflowId =
                UUID.randomUUID()
                        .toString();


        Map<String, String> taskIdsByKey =
                new LinkedHashMap<>();


        for (String key :
                tasksByKey.keySet()) {

            taskIdsByKey.put(
                    key,
                    UUID.randomUUID()
                            .toString()
            );
        }


        ForgeWorkflow workflow =
                new ForgeWorkflow(
                        workflowId,
                        request.name().trim()
                );


        List<ForgeTask> forgeTasks =
                new ArrayList<>();


        Map<String, ForgeTask> forgeTasksByKey =
                new LinkedHashMap<>();


        for (NormalizedTask task :
                tasksByKey.values()) {

            List<String> dependencyIds =
                    task.dependsOn()
                            .stream()
                            .map(
                                    taskIdsByKey::get
                            )
                            .toList();


            ForgeTask forgeTask =
                    new ForgeTask(
                            taskIdsByKey.get(
                                    task.key()
                            ),
                            task.command(),
                            task.arguments(),
                            task.maxAttempts(),
                            task.timeoutSeconds(),
                            dependencyIds,
                            workflowId,
                            task.key()
                    );


            if (dependencyIds.isEmpty()) {

                forgeTask.markPending();
            }
            else {

                forgeTask.markBlocked();
            }


            forgeTasks.add(
                    forgeTask
            );


            forgeTasksByKey.put(
                    task.key(),
                    forgeTask
            );
        }


        workflowRepository.save(
                workflow
        );


        taskRegistry.saveAll(
                forgeTasks
        );


        List<WorkflowTaskResponse> taskResponses =
                new ArrayList<>();


        for (NormalizedTask task :
                tasksByKey.values()) {

            ForgeTask forgeTask =
                    forgeTasksByKey.get(
                            task.key()
                    );


            taskResponses.add(
                    new WorkflowTaskResponse(
                            task.key(),
                            forgeTask.getId(),
                            forgeTask
                                    .getStatus()
                                    .name(),
                            task.dependsOn()
                    )
            );
        }


        return new WorkflowResponse(
                workflow.getId(),
                workflow.getName(),
                workflow.getCreatedAt(),
                determineWorkflowStatus(
                        forgeTasks
                ),
                taskResponses
        );
    }


    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflow(
            String workflowId) {

        ForgeWorkflow workflow =
                workflowRepository
                        .findById(
                                workflowId
                        )
                        .orElse(null);


        if (workflow == null) {

            return null;
        }


        List<ForgeTask> tasks =
                taskRegistry
                        .getByWorkflowId(
                                workflowId
                        );


        Map<String, String> taskKeyById =
                new HashMap<>();


        for (ForgeTask task :
                tasks) {

            taskKeyById.put(
                    task.getId(),
                    task.getWorkflowTaskKey()
            );
        }


        List<WorkflowTaskResponse> responses =
                new ArrayList<>();


        for (ForgeTask task :
                tasks) {

            List<String> dependencyKeys =
                    task.getDependsOnTaskIds()
                            .stream()
                            .map(
                                    taskKeyById::get
                            )
                            .toList();


            responses.add(
                    new WorkflowTaskResponse(
                            task.getWorkflowTaskKey(),
                            task.getId(),
                            task.getStatus()
                                    .name(),
                            dependencyKeys
                    )
            );
        }


        return new WorkflowResponse(
                workflow.getId(),
                workflow.getName(),
                workflow.getCreatedAt(),
                determineWorkflowStatus(
                        tasks
                ),
                responses
        );
    }


    public WorkflowResponse cancelWorkflow(
            String workflowId) {

        ForgeWorkflow workflow =
                workflowRepository
                        .findById(
                                workflowId
                        )
                        .orElse(null);


        if (workflow == null) {

            return null;
        }

        List<ForgeTask> tasks =
                taskRegistry
                        .getByWorkflowId(
                                workflowId
                        );

        /*
        * Persist workflow-level cancellation before
        * touching individual tasks.
        *
        * If the process fails during the sweep, the
        * cancellation intent itself survives.
        */
        workflow.requestCancellation();

        workflowRepository.saveAndFlush(
                workflow
        );

        for (ForgeTask task :
                tasks) {

            TaskStatus status =
                    task.getStatus();


            if (status == TaskStatus.SUCCEEDED
                    || status == TaskStatus.CANCELLED
                    || status == TaskStatus.SKIPPED) {

                continue;
            }


            /*
            * FAILED / LOST may still be waiting for an
            * automatic retry.
            *
            * There is no active process to signal here,
            * so cancellation can happen immediately at
            * the logical task level.
            */
            if (status == TaskStatus.FAILED
                    || status == TaskStatus.LOST) {

                task.requestCancellation();

                task.markCancelled();

                taskRegistry.save(
                        task
                );

                continue;
            }


            try {

                taskService.cancelTask(
                        task.getId()
                );
            }
            catch (IllegalArgumentException
                    | IllegalStateException exception) {
                /*
                 * Task may have changed state
                 * between our read and cancellation.
                 */
            }
        }


        return getWorkflow(
                workflowId
        );
    }
    @Transactional(readOnly = true)
    public List<WorkflowSummaryResponse> getWorkflows() {
    
        List<ForgeWorkflow> workflows =
                workflowRepository
                        .findAllByOrderByCreatedAtDesc();
    
    
        List<WorkflowSummaryResponse> responses =
                new ArrayList<>();
    
    
        for (ForgeWorkflow workflow :
                workflows) {
    
            List<ForgeTask> tasks =
                    taskRegistry
                            .getByWorkflowId(
                                    workflow.getId()
                            );
    
    
            responses.add(
                    new WorkflowSummaryResponse(
                            workflow.getId(),
                            workflow.getName(),
                            workflow.getCreatedAt(),
                            determineWorkflowStatus(
                                    tasks
                            ),
                            tasks.size()
                    )
            );
        }
    
    
        return responses;
    }
    @Transactional
    public WorkflowResponse retryWorkflow(
            String workflowId) {
    
        ForgeWorkflow workflow =
                workflowRepository
                        .findById(
                                workflowId
                        )
                        .orElse(null);
    
    
        if (workflow == null) {
    
            return null;
        }
    
    
        List<ForgeTask> tasks =
                taskRegistry
                        .getByWorkflowId(
                                workflowId
                        );
    
    
        /*
         * Do not retry a workflow that is still actively
         * progressing.
         *
         * FAILED / LOST are also considered active if
         * automatic retry budget remains.
         */
        for (ForgeTask task :
                tasks) {
    
            TaskStatus status =
                    task.getStatus();
    
    
            if (status == TaskStatus.CREATED
                    || status == TaskStatus.BLOCKED
                    || status == TaskStatus.PENDING
                    || status == TaskStatus.DISPATCHED
                    || status == TaskStatus.RUNNING) {
    
                throw new IllegalArgumentException(
                        "Workflow "
                                + workflowId
                                + " cannot be retried while task "
                                + task.getWorkflowTaskKey()
                                + " is "
                                + status
                );
            }
    
    
            if ((status == TaskStatus.FAILED
                    || status == TaskStatus.LOST)
                    && hasAutomaticRetryRemaining(
                            task
                    )) {
    
                throw new IllegalArgumentException(
                        "Workflow "
                                + workflowId
                                + " cannot be manually retried "
                                + "while automatic retries remain for task "
                                + task.getWorkflowTaskKey()
                );
            }
        }
    
    
        boolean resetAnyTask =
                false;
    
    
        /*
         * Successful work is deliberately preserved.
         *
         * Only unsuccessful / cancelled portions of
         * the graph are reopened.
         */
        for (ForgeTask task :
                tasks) {
    
            TaskStatus status =
                    task.getStatus();
    
    
            if (status == TaskStatus.FAILED
                    || status == TaskStatus.LOST
                    || status == TaskStatus.CANCELLED
                    || status == TaskStatus.SKIPPED) {
    
                task.resetForWorkflowRetry();
    
                resetAnyTask =
                        true;
            }
        }
    
    
        if (!resetAnyTask) {
    
            throw new IllegalArgumentException(
                    "Workflow "
                            + workflowId
                            + " has nothing to retry"
            );
        }
    
    
        /*
         * A workflow retry explicitly reopens a cancelled
         * workflow. Clear the durable workflow-level intent
         * before making its reset tasks visible to the
         * coordinators.
         */
        workflow.clearCancellationRequest();

        workflowRepository.save(
                workflow
        );

        /*
         * Save the whole reset atomically.
         *
         * Scheduled coordinators cannot observe a
         * partially reset DAG before this transaction
         * commits.
         */
        taskRegistry.saveAll(
                tasks
        );
    
    
        System.out.println(
                "↻ WORKFLOW RETRY QUEUED: workflow="
                        + workflowId
        );
    
    
        return getWorkflow(
                workflowId
        );
    }
    
    private WorkflowStatus determineWorkflowStatus(
            List<ForgeTask> tasks) {

        if (tasks.isEmpty()) {

            return WorkflowStatus.PENDING;
        }


        boolean allSucceeded =
                true;

        boolean active =
                false;

        boolean pending =
                false;

        boolean cancelled =
                false;

        boolean skipped =
                false;

        boolean permanentFailure =
                false;


        for (ForgeTask task :
                tasks) {

            TaskStatus status =
                    task.getStatus();


            if (status != TaskStatus.SUCCEEDED) {

                allSucceeded =
                        false;
            }


            switch (status) {

                case SUCCEEDED -> {
                }


                case DISPATCHED, RUNNING ->

                        active =
                                true;


                case CREATED, BLOCKED, PENDING ->

                        pending =
                                true;


                case CANCELLED ->

                        cancelled =
                                true;


                case SKIPPED ->

                        skipped =
                                true;


                case FAILED, LOST -> {

                    if (hasAutomaticRetryRemaining(
                            task)) {

                        active =
                                true;
                    }
                    else {

                        permanentFailure =
                                true;
                    }
                }
            }
        }


        if (allSucceeded) {

            return WorkflowStatus.SUCCEEDED;
        }


        if (permanentFailure) {

            return WorkflowStatus.FAILED;
        }


        if (cancelled) {

            return WorkflowStatus.CANCELLED;
        }


        if (skipped) {

            return WorkflowStatus.FAILED;
        }


        if (active) {

            return WorkflowStatus.RUNNING;
        }


        if (pending) {

            return WorkflowStatus.PENDING;
        }


        return WorkflowStatus.PENDING;
    }


    private boolean hasAutomaticRetryRemaining(
            ForgeTask task) {

        if (task.isCancelRequested()) {

            return false;
        }


        TaskAttempt latestAttempt =
                taskAttemptRegistry
                        .getLatestForTask(
                                task.getId()
                        );


        if (latestAttempt == null) {

            return false;
        }


        return latestAttempt
                .getAttemptNumber()
                < task.getMaxAttempts();
    }


    private void validateRequest(
            CreateWorkflowRequest request) {

        if (request == null) {

            throw new IllegalArgumentException(
                    "workflow request must not be null"
            );
        }


        if (request.name() == null
                || request.name().isBlank()) {

            throw new IllegalArgumentException(
                    "workflow name must not be empty"
            );
        }


        if (request.tasks() == null
                || request.tasks().isEmpty()) {

            throw new IllegalArgumentException(
                    "workflow must contain at least one task"
            );
        }


        if (request.tasks().size()
                > MAX_WORKFLOW_TASKS) {

            throw new IllegalArgumentException(
                    "workflow may contain at most "
                            + MAX_WORKFLOW_TASKS
                            + " tasks"
            );
        }
    }


    private Map<String, NormalizedTask>
            normalizeTasks(
                    List<WorkflowTaskRequest> requests) {

        Map<String, NormalizedTask> result =
                new LinkedHashMap<>();


        for (WorkflowTaskRequest request :
                requests) {

            if (request == null) {

                throw new IllegalArgumentException(
                        "workflow task must not be null"
                );
            }


            if (request.key() == null
                    || request.key().isBlank()) {

                throw new IllegalArgumentException(
                        "workflow task key must not be empty"
                );
            }


            String key =
                    request.key()
                            .trim();


            if (result.containsKey(
                    key)) {

                throw new IllegalArgumentException(
                        "duplicate workflow task key: "
                                + key
                );
            }


            if (request.command() == null
                    || request.command().isBlank()) {

                throw new IllegalArgumentException(
                        "command must not be empty for task "
                                + key
                );
            }


            int maxAttempts =
                    request.maxAttempts() == null
                            ? 1
                            : request.maxAttempts();


            if (maxAttempts < 1
                    || maxAttempts > 10) {

                throw new IllegalArgumentException(
                        "maxAttempts must be between "
                                + "1 and 10 for task "
                                + key
                );
            }


            int timeoutSeconds =
                    request.timeoutSeconds() == null
                            ? 0
                            : request.timeoutSeconds();


            if (timeoutSeconds < 0
                    || timeoutSeconds > 86400) {

                throw new IllegalArgumentException(
                        "timeoutSeconds must be between "
                                + "0 and 86400 for task "
                                + key
                );
            }


            List<String> arguments =
                    request.arguments() == null
                            ? List.of()
                            : List.copyOf(
                                    request.arguments()
                            );


            List<String> dependencies =
                    normalizeDependencies(
                            key,
                            request.dependsOn()
                    );


            result.put(
                    key,
                    new NormalizedTask(
                            key,
                            request.command(),
                            arguments,
                            maxAttempts,
                            timeoutSeconds,
                            dependencies
                    )
            );
        }


        return result;
    }


    private List<String> normalizeDependencies(
            String taskKey,
            List<String> dependencies) {

        if (dependencies == null) {

            return List.of();
        }


        if (dependencies.size()
                > MAX_DEPENDENCIES_PER_TASK) {

            throw new IllegalArgumentException(
                    "task "
                            + taskKey
                            + " may have at most "
                            + MAX_DEPENDENCIES_PER_TASK
                            + " dependencies"
            );
        }


        Set<String> normalized =
                new LinkedHashSet<>();


        for (String dependency :
                dependencies) {

            if (dependency == null
                    || dependency.isBlank()) {

                throw new IllegalArgumentException(
                        "dependency key must not be empty "
                                + "for task "
                                + taskKey
                );
            }


            normalized.add(
                    dependency.trim()
            );
        }


        return List.copyOf(
                normalized
        );
    }


    private void validateDependencies(
            Map<String, NormalizedTask> tasksByKey) {

        for (NormalizedTask task :
                tasksByKey.values()) {

            for (String dependency :
                    task.dependsOn()) {

                if (!tasksByKey.containsKey(
                        dependency)) {

                    throw new IllegalArgumentException(
                            "task "
                                    + task.key()
                                    + " depends on unknown task "
                                    + dependency
                    );
                }


                if (task.key().equals(
                        dependency)) {

                    throw new IllegalArgumentException(
                            "task "
                                    + task.key()
                                    + " cannot depend on itself"
                    );
                }
            }
        }
    }


    private void validateAcyclic(
            Map<String, NormalizedTask> tasksByKey) {

        Map<String, Integer> state =
                new HashMap<>();


        for (String key :
                tasksByKey.keySet()) {

            visit(
                    key,
                    tasksByKey,
                    state
            );
        }
    }


    private void visit(
            String key,
            Map<String, NormalizedTask> tasksByKey,
            Map<String, Integer> state) {

        int currentState =
                state.getOrDefault(
                        key,
                        0
                );


        if (currentState == 2) {

            return;
        }


        if (currentState == 1) {

            throw new IllegalArgumentException(
                    "workflow contains a dependency cycle "
                            + "involving task "
                            + key
            );
        }


        state.put(
                key,
                1
        );


        NormalizedTask task =
                tasksByKey.get(
                        key
                );


        for (String dependency :
                task.dependsOn()) {

            visit(
                    dependency,
                    tasksByKey,
                    state
            );
        }


        state.put(
                key,
                2
        );
    }


    private record NormalizedTask(
            String key,
            String command,
            List<String> arguments,
            int maxAttempts,
            int timeoutSeconds,
            List<String> dependsOn
    ) {
    }
}
