package dev.forge.controller.grpc;

import dev.forge.proto.ControllerMessage;

import io.grpc.stub.StreamObserver;

import java.util.concurrent.atomic.AtomicInteger;


public class WorkerState {

    private final String workerId;
    private final String hostname;
    private final int cpuCores;
    private final long memoryBytes;
    private final String operatingSystem;

    private volatile long lastHeartbeat;
    private volatile double cpuUsage;
    private volatile long memoryUsed;
    private volatile int runningTasks;
    private volatile boolean online;

    /*
     * Tasks dispatched by the controller that have not
     * finished yet.
     *
     * This includes tasks currently running as well as
     * tasks waiting in the worker's local queue.
     */
    private final AtomicInteger outstandingTasks =
            new AtomicInteger(0);

    /*
     * Long-lived gRPC connection used to send commands
     * to this worker.
     */
    private volatile StreamObserver<ControllerMessage> commandStream;


    public WorkerState(
            String workerId,
            String hostname,
            int cpuCores,
            long memoryBytes,
            String operatingSystem) {

        this.workerId = workerId;
        this.hostname = hostname;
        this.cpuCores = cpuCores;
        this.memoryBytes = memoryBytes;
        this.operatingSystem = operatingSystem;

        this.lastHeartbeat =
                System.currentTimeMillis();

        this.online = true;
    }


    public synchronized boolean sendCommand(
            ControllerMessage message) {

        if (!online || commandStream == null) {
            return false;
        }

        try {

            commandStream.onNext(message);

            return true;
        }
        catch (RuntimeException exception) {

            System.err.println(
                    "Failed to send command to "
                            + workerId
                            + ": "
                            + exception.getMessage()
            );

            commandStream = null;

            return false;
        }
    }


    // =========================================================
    // Controller-side task reservations
    // =========================================================

    public void reserveTask() {

        outstandingTasks.incrementAndGet();
    }


    public void releaseTask() {

        outstandingTasks.updateAndGet(
                current ->
                        Math.max(
                                0,
                                current - 1
                        )
        );
    }


    public int getOutstandingTasks() {

        return outstandingTasks.get();
    }


    /*
     * runningTasks:
     *     latest value reported by worker heartbeat
     *
     * outstandingTasks:
     *     tasks the controller knows it has assigned
     *
     * We use max() instead of adding them because the same
     * running task may appear in both numbers.
     */
    public int getEffectiveLoad() {

        return Math.max(
                runningTasks,
                outstandingTasks.get()
        );
    }


    // =========================================================
    // Command stream
    // =========================================================

    public boolean hasCommandStream() {

        return commandStream != null;
    }


    public void setCommandStream(
            StreamObserver<ControllerMessage> commandStream) {

        this.commandStream = commandStream;
    }


    public StreamObserver<ControllerMessage> getCommandStream() {

        return commandStream;
    }


    // =========================================================
    // Worker identity
    // =========================================================

    public String getWorkerId() {

        return workerId;
    }


    public String getHostname() {

        return hostname;
    }


    public int getCpuCores() {

        return cpuCores;
    }


    public long getMemoryBytes() {

        return memoryBytes;
    }


    public String getOperatingSystem() {

        return operatingSystem;
    }


    // =========================================================
    // Heartbeat state
    // =========================================================

    public long getLastHeartbeat() {

        return lastHeartbeat;
    }


    public void updateHeartbeat(
            double cpuUsage,
            long memoryUsed,
            int runningTasks) {

        this.cpuUsage = cpuUsage;
        this.memoryUsed = memoryUsed;
        this.runningTasks = runningTasks;

        this.lastHeartbeat =
                System.currentTimeMillis();

        this.online = true;
    }


    public void setOnline(
            boolean online) {

        this.online = online;
    }


    public boolean isOnline() {

        return online;
    }


    public double getCpuUsage() {

        return cpuUsage;
    }


    public long getMemoryUsed() {

        return memoryUsed;
    }


    public int getRunningTasks() {

        return runningTasks;
    }
}