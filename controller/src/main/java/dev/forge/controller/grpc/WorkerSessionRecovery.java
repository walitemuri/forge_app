package dev.forge.controller.grpc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(
        name = "worker_session_recoveries",
        uniqueConstraints = @UniqueConstraint(
                name = "worker_session_recoveries_unique_owner",
                columnNames = {
                        "worker_id",
                        "session_id"
                }
        )
)
public class WorkerSessionRecovery {

    @Id
    private String id;


    @Column(
            name = "worker_id",
            nullable = false
    )
    private String workerId;


    @Column(
            name = "session_id",
            nullable = false
    )
    private String sessionId;


    @Column(
            name = "recover_after",
            nullable = false
    )
    private Instant recoverAfter;


    protected WorkerSessionRecovery() {
    }


    public WorkerSessionRecovery(
            String workerId,
            String sessionId,
            Instant recoverAfter) {

        this.id =
                UUID.randomUUID()
                        .toString();

        this.workerId =
                workerId;

        this.sessionId =
                sessionId;

        this.recoverAfter =
                recoverAfter;
    }


    public String getId() {
        return id;
    }


    public String getWorkerId() {
        return workerId;
    }


    public String getSessionId() {
        return sessionId;
    }


    public Instant getRecoverAfter() {
        return recoverAfter;
    }


    public void reschedule(
            Instant recoverAfter) {

        this.recoverAfter =
                recoverAfter;
    }
}
