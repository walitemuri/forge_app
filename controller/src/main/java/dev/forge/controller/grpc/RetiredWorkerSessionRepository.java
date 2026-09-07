package dev.forge.controller.grpc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface RetiredWorkerSessionRepository
        extends JpaRepository<
                RetiredWorkerSession,
                String> {

    boolean existsByWorkerIdAndSessionId(
            String workerId,
            String sessionId
    );


    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    INSERT INTO retired_worker_sessions (
                        id,
                        worker_id,
                        session_id,
                        retired_at
                    )
                    VALUES (
                        :id,
                        :workerId,
                        :sessionId,
                        NOW()
                    )
                    ON CONFLICT (
                        worker_id,
                        session_id
                    )
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("id")
            String id,

            @Param("workerId")
            String workerId,

            @Param("sessionId")
            String sessionId
    );
}
