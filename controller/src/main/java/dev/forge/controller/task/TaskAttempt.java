package dev.forge.controller.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;


@Entity
@Table(name = "task_attempts")
public class TaskAttempt {

    @Id
    private String id;

    @Column(
            name = "task_id",
            nullable = false
    )
    private String taskId;

    @Column(
            name = "attempt_number",
            nullable = false
    )
    private int attemptNumber;

    @Column(name = "worker_id")
    private String workerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskAttemptStatus status;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(columnDefinition = "TEXT")
    private String stdout;

    @Column(columnDefinition = "TEXT")
    private String stderr;


    protected TaskAttempt() {
    }


    public TaskAttempt(
            String id,
            String taskId,
            int attemptNumber) {

        this.id = id;
        this.taskId = taskId;
        this.attemptNumber = attemptNumber;

        this.status =
                TaskAttemptStatus.CREATED;

        this.createdAt =
                Instant.now();
    }
    public void failBeforeStart(
                String stderr) {

        this.stderr =
                stderr;

        this.finishedAt =
                Instant.now();

        this.status =
                TaskAttemptStatus.FAILED;
    }


    public String getId() {
        return id;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getWorkerId() {
        return workerId;
    }

    public TaskAttemptStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
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


    public void markDispatched(
            String workerId) {

        this.workerId =
                workerId;

        this.status =
                TaskAttemptStatus.DISPATCHED;
    }


    public void markRunning() {

        this.status =
                TaskAttemptStatus.RUNNING;

        this.startedAt =
                Instant.now();
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

        this.finishedAt =
                Instant.now();

        this.status =
                success
                        ? TaskAttemptStatus.SUCCEEDED
                        : TaskAttemptStatus.FAILED;
    }


    public void markLost() {

        this.status =
                TaskAttemptStatus.LOST;

        this.finishedAt =
                Instant.now();
    }
}
