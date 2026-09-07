package dev.forge.controller.grpc;

import dev.forge.controller.task.WorkerFailureService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class WorkerSessionRecoveryCoordinator {

    /*
     * Give a replacement process time to load and replay
     * durable events created by the previous incarnation.
     */
    private static final long
            RECOVERY_GRACE_MS = 10_000;


    private final WorkerFailureService
            workerFailureService;


    private final Map<SessionKey, Long>
            pendingRecoveries =
                    new ConcurrentHashMap<>();


    public WorkerSessionRecoveryCoordinator(
            WorkerFailureService workerFailureService) {

        this.workerFailureService =
                workerFailureService;
    }


    public void scheduleRecovery(
            String workerId,
            String sessionId) {

        if (workerId == null
                || workerId.isBlank()
                || sessionId == null
                || sessionId.isBlank()) {

            return;
        }


        SessionKey key =
                new SessionKey(
                        workerId,
                        sessionId
                );


        pendingRecoveries.put(
                key,
                System.currentTimeMillis()
                        + RECOVERY_GRACE_MS
        );


        System.out.println(
                "Scheduled worker-session recovery: worker="
                        + workerId
                        + " session="
                        + sessionId
                        + " graceMs="
                        + RECOVERY_GRACE_MS
        );
    }


    public void cancelRecovery(
            String workerId,
            String sessionId) {

        if (workerId == null
                || sessionId == null) {

            return;
        }


        pendingRecoveries.remove(
                new SessionKey(
                        workerId,
                        sessionId
                )
        );
    }


    @Scheduled(fixedRate = 1000)
    public void reconcileExpiredSessions() {

        long now =
                System.currentTimeMillis();


        for (Map.Entry<SessionKey, Long> entry :
                pendingRecoveries.entrySet()) {

            if (entry.getValue() > now) {

                continue;
            }


            SessionKey key =
                    entry.getKey();


            if (!pendingRecoveries.remove(
                    key,
                    entry.getValue())) {

                continue;
            }


            WorkerState current =
                    WorkerRegistry.get(
                            key.workerId()
                    );


            /*
             * The old session somehow became authoritative
             * again before its grace period expired.
             */
            if (current != null
                    && current.hasSession(
                            key.sessionId()
                    )
                    && current.isOnline()
                    && current.hasCommandStream()) {

                continue;
            }


            System.out.println(
                    "Worker-session replay grace expired: worker="
                            + key.workerId()
                            + " session="
                            + key.sessionId()
            );


            workerFailureService
                    .handleWorkerSessionLost(
                            key.workerId(),
                            key.sessionId()
                    );
        }
    }


    private record SessionKey(
            String workerId,
            String sessionId) {
    }
}
