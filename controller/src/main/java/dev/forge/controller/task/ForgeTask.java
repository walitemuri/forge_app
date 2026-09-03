package dev.forge.controller.task;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


@Entity
@Table(name = "forge_tasks")
public class ForgeTask {

    @Id
    private String id;


    @Column(nullable = false)
    private String command;


    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "forge_task_arguments",
            joinColumns = @JoinColumn(name = "task_id")
    )
    @OrderColumn(name = "argument_index")
    @Column(name = "argument_value")
    private List<String> arguments =
            new ArrayList<>();


    @Column(name = "workflow_id")
    private String workflowId;


    @Column(name = "workflow_task_key")
    private String workflowTaskKey;


    @Column(nullable = false)
    private Instant createdAt;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;


    private String workerId;


    private Integer exitCode;


    @Column(columnDefinition = "TEXT")
    private String stdout;


    @Column(columnDefinition = "TEXT")
    private String stderr;


    @Column(
            name = "timeout_seconds",
            nullable = false
    )
    private int timeoutSeconds;


    @Column(
            name = "max_attempts",
            nullable = false
    )
    private int maxAttempts;


    @Column(
            name = "cancel_requested",
            nullable = false
    )
    private boolean cancelRequested;


    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "task_dependencies",
            joinColumns = @JoinColumn(name = "task_id")
    )
    @Column(
            name = "depends_on_task_id",
            nullable = false
    )
    private Set<String> dependsOnTaskIds =
            new LinkedHashSet<>();


    protected ForgeTask() {
    }


    /*
     * Standalone task constructor.
     *
     * Existing task submission continues using this.
     */
    public ForgeTask(
            String id,
            String command,
            List<String> arguments,
            int maxAttempts,
            int timeoutSeconds,
            List<String> dependsOnTaskIds) {

        this(
                id,
                command,
                arguments,
                maxAttempts,
                timeoutSeconds,
                dependsOnTaskIds,
                null,
                null
        );
    }


    /*
     * Workflow task constructor.
     */
    public ForgeTask(
            String id,
            String command,
            List<String> arguments,
            int maxAttempts,
            int timeoutSeconds,
            List<String> dependsOnTaskIds,
            String workflowId,
            String workflowTaskKey) {

        /*
         * A task either belongs to a workflow
         * with a task key, or it belongs to no workflow.
         */
        if ((workflowId == null)
                != (workflowTaskKey == null)) {

            throw new IllegalArgumentException(
                    "workflowId and workflowTaskKey "
                            + "must either both be set or both be null"
            );
        }


        this.id =
                id;

        this.command =
                command;

        this.arguments =
                arguments == null
                        ? new ArrayList<>()
                        : new ArrayList<>(
                                arguments
                        );

        this.maxAttempts =
                maxAttempts;

        this.timeoutSeconds =
                timeoutSeconds;

        this.createdAt =
                Instant.now();

        this.status =
                TaskStatus.CREATED;

        this.dependsOnTaskIds =
                dependsOnTaskIds == null
                        ? new LinkedHashSet<>()
                        : new LinkedHashSet<>(
                                dependsOnTaskIds
                        );

        this.workflowId =
                workflowId;

        this.workflowTaskKey =
                workflowTaskKey;
    }


    public String getId() {
        return id;
    }


    public String getCommand() {
        return command;
    }


    public List<String> getArguments() {
        return List.copyOf(
                arguments
        );
    }


    public String getWorkflowId() {
        return workflowId;
    }


    public String getWorkflowTaskKey() {
        return workflowTaskKey;
    }


    public List<String> getDependsOnTaskIds() {
        return List.copyOf(
                dependsOnTaskIds
        );
    }


    public Instant getCreatedAt() {
        return createdAt;
    }


    public TaskStatus getStatus() {
        return status;
    }


    public void setStatus(
            TaskStatus status) {

        this.status =
                status;
    }


    public String getWorkerId() {
        return workerId;
    }


    public void setWorkerId(
            String workerId) {

        this.workerId =
                workerId;
    }


    public Integer getExitCode() {
        return exitCode;
    }


    public String getStdout() {
        return stdout;
    }


    public String getStderr() {
        return stderr;
    }


    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }


    public int getMaxAttempts() {
        return maxAttempts;
    }


    public boolean isCancelRequested() {
        return cancelRequested;
    }


    public void markRunning() {

        this.status =
                TaskStatus.RUNNING;
    }


    public void markBlocked() {

        this.status =
                TaskStatus.BLOCKED;

        this.workerId =
                null;
    }


    public void markLost() {

        this.status =
                TaskStatus.LOST;
    }


    public void complete(
            boolean success,
            int exitCode,
            String stdout,
            String stderr) {

        this.exitCode =
                exitCode;

        this.stdout =
                stdout;

        this.stderr =
                stderr;

        this.status =
                success
                        ? TaskStatus.SUCCEEDED
                        : TaskStatus.FAILED;
    }


    public void markDispatched(
            String workerId) {

        this.workerId =
                workerId;

        this.status =
                TaskStatus.DISPATCHED;


        /*
         * A retry represents a new physical execution.
         *
         * Don't expose the previous attempt's result
         * as if it belonged to the new one.
         */
        this.exitCode =
                null;

        this.stdout =
                null;

        this.stderr =
                null;
    }


    public void requestCancellation() {

        this.cancelRequested =
                true;
    }


    public void clearCancellationRequest() {

        this.cancelRequested =
                false;
    }


    public void markCancelled() {

        this.status =
                TaskStatus.CANCELLED;
    }


    public void markCancelled(
            int exitCode,
            String stdout,
            String stderr) {

        this.exitCode =
                exitCode;

        this.stdout =
                stdout;

        this.stderr =
                stderr;

        this.cancelRequested =
                true;

        this.status =
                TaskStatus.CANCELLED;
    }


    public void markPending() {

        this.status =
                TaskStatus.PENDING;

        this.workerId =
                null;
    }


    public void markSkipped() {

        this.status =
                TaskStatus.SKIPPED;

        this.workerId =
                null;
    }
}