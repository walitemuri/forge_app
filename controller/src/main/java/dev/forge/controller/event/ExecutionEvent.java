package dev.forge.controller.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;


@Entity
@Table(name = "execution_events")
public class ExecutionEvent {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY
    )
    private Long id;


    @Enumerated(EnumType.STRING)
    @Column(
            name = "event_type",
            nullable = false
    )
    private ExecutionEventType eventType;


    @Column(name = "workflow_id")
    private String workflowId;


    @Column(name = "task_id")
    private String taskId;


    @Column(name = "attempt_id")
    private String attemptId;


    @Column(name = "worker_id")
    private String workerId;


    @Column(columnDefinition = "TEXT")
    private String message;


    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;


    protected ExecutionEvent() {
    }


    public ExecutionEvent(
            ExecutionEventType eventType,
            String workflowId,
            String taskId,
            String attemptId,
            String workerId,
            String message) {

        this.eventType =
                eventType;

        this.workflowId =
                workflowId;

        this.taskId =
                taskId;

        this.attemptId =
                attemptId;

        this.workerId =
                workerId;

        this.message =
                message;

        this.createdAt =
                Instant.now();
    }


    public Long getId() {
        return id;
    }


    public ExecutionEventType getEventType() {
        return eventType;
    }


    public String getWorkflowId() {
        return workflowId;
    }


    public String getTaskId() {
        return taskId;
    }


    public String getAttemptId() {
        return attemptId;
    }


    public String getWorkerId() {
        return workerId;
    }


    public String getMessage() {
        return message;
    }


    public Instant getCreatedAt() {
        return createdAt;
    }
}
