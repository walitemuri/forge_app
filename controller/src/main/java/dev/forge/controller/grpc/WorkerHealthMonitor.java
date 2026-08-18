package dev.forge.controller.grpc;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkerHealthMonitor {

    private static final long WORKER_TIMEOUT_MS = 15_000;

    @Scheduled(fixedRate = 5000)
    public void checkWorkers() {

        long now = System.currentTimeMillis();

        for (WorkerState worker :
                WorkerRegistry.getWorkers().values()) {

            long timeSinceHeartbeat =
                    now - worker.getLastHeartbeat();

            if (worker.isOnline()
                    && timeSinceHeartbeat > WORKER_TIMEOUT_MS) {

                worker.setOnline(false);

                System.out.println();
                System.out.println(
                        "⚠ WORKER LOST: "
                                + worker.getWorkerId()
                );

                System.out.println(
                        "Last heartbeat "
                                + timeSinceHeartbeat
                                + " ms ago"
                );

                System.out.println();
            }
        }
    }
}