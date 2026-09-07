package dev.forge.controller.grpc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface WorkerSessionRecoveryRepository
        extends JpaRepository<
                WorkerSessionRecovery,
                String> {

    Optional<WorkerSessionRecovery>
    findByWorkerIdAndSessionId(
            String workerId,
            String sessionId
    );


    List<WorkerSessionRecovery>
    findByRecoverAfterLessThanEqualOrderByRecoverAfterAsc(
            Instant recoverAfter
    );


    void deleteByWorkerIdAndSessionId(
            String workerId,
            String sessionId
    );
}
