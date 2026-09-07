package dev.forge.controller.grpc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface WorkerAuthorityRepository
        extends JpaRepository<
                WorkerAuthority,
                String> {

    /*
     * First process ever seen for this stable worker ID wins.
     *
     * ON CONFLICT is important:
     * two simultaneous initial registrations cannot both
     * become authoritative.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    INSERT INTO worker_authorities (
                        worker_id,
                        session_id,
                        updated_at
                    )
                    VALUES (
                        :workerId,
                        :sessionId,
                        NOW()
                    )
                    ON CONFLICT (worker_id)
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("workerId")
            String workerId,

            @Param("sessionId")
            String sessionId
    );


    /*
     * Transfer authority only if the caller still owns the
     * exact previous session it expects.
     *
     * This is effectively a compare-and-swap.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    UPDATE worker_authorities
                    SET
                        session_id = :newSessionId,
                        updated_at = NOW()
                    WHERE worker_id = :workerId
                      AND session_id = :expectedSessionId
                    """,
            nativeQuery = true
    )
    int transferAuthority(
            @Param("workerId")
            String workerId,

            @Param("expectedSessionId")
            String expectedSessionId,

            @Param("newSessionId")
            String newSessionId
    );
}
