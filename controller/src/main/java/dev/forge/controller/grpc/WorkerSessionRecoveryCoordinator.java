package dev.forge.controller.grpc;

import dev.forge.controller.task.WorkerFailureService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;


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

    private final WorkerSessionRecoveryRepository
            recoveryRepository;


    public WorkerSessionRecoveryCoordinator(
            WorkerFailureService workerFailureService,
            WorkerSessionRecoveryRepository recoveryRepository) {

        this.workerFailureService =
                workerFailureService;

        this.recoveryRepository =
                recoveryRepository;
    }


    @Transactional
    public void scheduleRecovery(
            String workerId,
            String sessionId) {

        if (workerId == null
                || workerId.isBlank()
                || sessionId == null
                || sessionId.isBlank()) {

            return;
        }


        Instant recoverAfter =
                Instant.now()
                        .plusMillis(
                                RECOVERY_GRACE_MS
                        );


        WorkerSessionRecovery recovery =
                recoveryRepository
                        .findByWorkerIdAndSessionId(
                                workerId,
                                sessionId
                        )
                        .orElseGet(
                                () ->
                                        new WorkerSessionRecovery(
                                                workerId,
                                                sessionId,
                                                recoverAfter
                                        )
                        );


        recovery.reschedule(
                recoverAfter
        );


        recoveryRepository.save(
                recovery
        );


        System.out.println(
                "Scheduled DURABLE worker-session recovery: worker="
                        + workerId
                        + " session="
                        + sessionId
                        + " recoverAfter="
                        + recoverAfter
        );
    }


    @Transactional
    public void cancelRecovery(
            String workerId,
            String sessionId) {

        if (workerId == null
                || sessionId == null) {

            return;
        }


        recoveryRepository
                .deleteByWorkerIdAndSessionId(
                        workerId,
                        sessionId
                );
    }


    @Transactional(readOnly = true)
    public boolean hasPendingRecovery(
            String workerId,
            String sessionId,
            Instant now) {

        if (workerId == null
                || sessionId == null) {

            return false;
        }


        return recoveryRepository
                .findByWorkerIdAndSessionId(
                        workerId,
                        sessionId
                )
                .map(
                        recovery ->
                                recovery
                                        .getRecoverAfter()
                                        .isAfter(now)
                )
                .orElse(false);
    }


    @Scheduled(fixedRate = 1000)
    @Transactional
    public void reconcileExpiredSessions() {

        Instant now =
                Instant.now();


        List<WorkerSessionRecovery>
                expiredRecoveries =
                recoveryRepository
                        .findByRecoverAfterLessThanEqualOrderByRecoverAfterAsc(
                                now
                        );


        for (WorkerSessionRecovery recovery :
                expiredRecoveries) {

            WorkerState current =
                    WorkerRegistry.get(
                            recovery.getWorkerId()
                    );


            /*
             * The session became authoritative again before
             * the persisted grace period expired.
             */
            if (current != null
                    && current.hasSession(
                            recovery.getSessionId()
                    )
                    && current.isOnline()
                    && current.hasCommandStream()) {

                recoveryRepository.delete(
                        recovery
                );

                continue;
            }


            System.out.println(
                    "Durable worker-session replay grace expired: worker="
                            + recovery.getWorkerId()
                            + " session="
                            + recovery.getSessionId()
            );


            workerFailureService
                    .handleWorkerSessionLost(
                            recovery.getWorkerId(),
                            recovery.getSessionId()
                    );


            /*
             * Delete only after recovery state changes have
             * succeeded. Because this whole method is one
             * transaction, a failure rolls back both sides.
             */
            recoveryRepository.delete(
                    recovery
            );
        }
    }
}
