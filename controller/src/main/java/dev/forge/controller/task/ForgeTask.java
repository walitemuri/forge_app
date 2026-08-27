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
import jakarta.persistence.Lob;
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

    @Lob
    private String stdout;

    @Lob
    private String stderr;


    /*
     * Required by JPA.
     */
    protected ForgeTask() {
    }


    public ForgeTask(
            String id,
            String command,
            List<String> arguments) {

        this.id = id;
        this.command = command;

        this.arguments =
                new ArrayList<>(arguments);

        this.createdAt =
                Instant.now();

        this.status =
                TaskStatus.CREATED;
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
}