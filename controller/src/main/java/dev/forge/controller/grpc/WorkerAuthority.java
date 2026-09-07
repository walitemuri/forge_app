package dev.forge.controller.grpc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;


@Entity
@Table(name = "worker_authorities")
public class WorkerAuthority {

    @Id
    @Column(name = "worker_id")
    private String workerId;


    @Column(
            name = "session_id",
            nullable = false
    )
    private String sessionId;


    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;


    protected WorkerAuthority() {
    }


    public String getWorkerId() {
        return workerId;
    }


    public String getSessionId() {
        return sessionId;
    }


    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
