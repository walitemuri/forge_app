package dev.forge.controller.task;

import java.time.Instant;
import java.util.List;

public class ForgeTask {

    private final String id;
    private final String command;
    private final List<String> arguments;
    private final Instant createdAt;

    private volatile TaskStatus status;
    private volatile String workerId;

    private volatile Integer exitCode;
    private volatile String stdout;
    private volatile String stderr;


    public ForgeTask(
            String id,
            String command,
            List<String> arguments) {

        this.id = id;
        this.command = command;
        this.arguments = List.copyOf(arguments);
        this.createdAt = Instant.now();

        this.status = TaskStatus.CREATED;
    }


    public String getId() {
        return id;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArguments() {
        return arguments;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
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
        this.status = TaskStatus.RUNNING;
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