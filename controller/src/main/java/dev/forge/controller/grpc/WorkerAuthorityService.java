package dev.forge.controller.grpc;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
public class WorkerAuthorityService {

    private final WorkerAuthorityRepository
            repository;


    public WorkerAuthorityService(
            WorkerAuthorityRepository repository) {

        this.repository =
                repository;
    }


    /*
     * Return the durable authoritative session for a stable
     * worker ID, or null if Forge has never seen that worker.
     */
    @Transactional(readOnly = true)
    public String getAuthoritativeSession(
            String workerId) {

        return repository
                .findById(
                        workerId
                )
                .map(
                        WorkerAuthority::getSessionId
                )
                .orElse(
                        null
                );
    }


    /*
     * Claim a worker ID only if it does not already have a
     * durable owner.
     *
     * We always re-read afterward because another process may
     * have won the INSERT race.
     */
    @Transactional
    public boolean claimIfUnowned(
            String workerId,
            String sessionId) {

        repository.insertIfAbsent(
                workerId,
                sessionId
        );


        String authoritativeSession =
                repository
                        .findById(
                                workerId
                        )
                        .map(
                                WorkerAuthority::getSessionId
                        )
                        .orElse(
                                null
                        );


        return sessionId.equals(
                authoritativeSession
        );
    }


    /*
     * Atomically transfer:
     *
     *     expected old session -> new session
     *
     * If zero rows change, somebody else changed authority and
     * this takeover must be rejected.
     */
    @Transactional
    public boolean transferAuthority(
            String workerId,
            String expectedSessionId,
            String newSessionId) {

        int changed =
                repository.transferAuthority(
                        workerId,
                        expectedSessionId,
                        newSessionId
                );


        return changed == 1;
    }
}
