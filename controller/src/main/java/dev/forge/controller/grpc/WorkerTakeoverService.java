package dev.forge.controller.grpc;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
public class WorkerTakeoverService {

    private final WorkerSessionRecoveryCoordinator
            recoveryCoordinator;

    private final WorkerAuthorityService
            authorityService;

    private final WorkerSessionHistoryService
            sessionHistoryService;


    public WorkerTakeoverService(
            WorkerSessionRecoveryCoordinator
                    recoveryCoordinator,
            WorkerAuthorityService
                    authorityService,
            WorkerSessionHistoryService
                    sessionHistoryService) {

        this.recoveryCoordinator =
                recoveryCoordinator;

        this.authorityService =
                authorityService;

        this.sessionHistoryService =
                sessionHistoryService;
    }


    /*
     * Atomically perform the durable half of a worker
     * incarnation takeover:
     *
     *     1. persist replay grace for old session A
     *     2. transfer durable authority A -> B
     *
     * Both operations use Spring's default REQUIRED
     * propagation, so their existing @Transactional methods
     * join THIS transaction instead of committing separately.
     *
     * The method deliberately throws if the compare-and-swap
     * fails. A RuntimeException causes Spring to roll back the
     * recovery row too.
     */
    @Transactional
    public void takeover(
            String workerId,
            String oldSessionId,
            String newSessionId) {

        recoveryCoordinator
                .scheduleRecovery(
                        workerId,
                        oldSessionId
                );


        /*
         * A superseded process incarnation is permanently
         * stale. Keep that fact even after recovery grace has
         * expired and been deleted.
         */
        sessionHistoryService
                .retire(
                        workerId,
                        oldSessionId
                );


        boolean transferred =
                authorityService
                        .transferAuthority(
                                workerId,
                                oldSessionId,
                                newSessionId
                        );


        if (!transferred) {

            throw new WorkerTakeoverConflictException(
                    "Worker authority changed concurrently: worker="
                            + workerId
                            + " expectedSession="
                            + oldSessionId
                            + " requestedSession="
                            + newSessionId
            );
        }
    }
}
