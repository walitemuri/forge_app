package dev.forge.controller.grpc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;


@Entity
@Table(name = "retired_worker_sessions")
public class RetiredWorkerSession {

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
            name = "retired_at",
            nullable = false
    )
    private Instant retiredAt;


    protected RetiredWorkerSession() {
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


    public Instant getRetiredAt() {
        return retiredAt;
    }
}
