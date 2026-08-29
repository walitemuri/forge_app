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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


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
    @Column(name = "argument_value")
    private List<String> arguments =
            new ArrayList<>();

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
    /*
     * Required by JPA.
     */
    protected ForgeTask() {
    }


    public ForgeTask(
        String id,
        String command,
        List<String> arguments,
        int maxAttempts,
        int timeoutSeconds) {

        this.id = id;
        this.command = command;

        this.maxAttempts =
                maxAttempts;

        this.timeoutSeconds =
                timeoutSeconds;

        this.arguments =
                new ArrayList<>(arguments);

        this.createdAt =
                Instant.now();

        this.status =
                TaskStatus.CREATED;
    }
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getId() {
        return id;
    }


    public String getCommand() {
        return command;
    }


    public List<String> getArguments() {
        return List.copyOf(arguments);
    }


    public Instant getCreatedAt() {
        return createdAt;
    }


    public TaskStatus getStatus() {
        return status;
    }


    public void setStatus(
            TaskStatus status) {

        this.status = status;
    }


    public String getWorkerId() {
        return workerId;
    }


    public void setWorkerId(
            String workerId) {

        this.workerId = workerId;
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


    public void markRunning() {
        this.status =
                TaskStatus.RUNNING;
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

        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;

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
    public boolean isCancelRequested() {

    return cancelRequested;
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
}