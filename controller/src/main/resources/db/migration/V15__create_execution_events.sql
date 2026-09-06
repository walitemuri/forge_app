CREATE TABLE execution_events (
    id BIGSERIAL PRIMARY KEY,

    event_type VARCHAR(64) NOT NULL,

    workflow_id VARCHAR(255),
    task_id VARCHAR(255),
    attempt_id VARCHAR(255),
    worker_id VARCHAR(255),

    message TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_execution_events_workflow
        FOREIGN KEY (workflow_id)
        REFERENCES forge_workflows(id),

    CONSTRAINT fk_execution_events_task
        FOREIGN KEY (task_id)
        REFERENCES forge_tasks(id),

    CONSTRAINT fk_execution_events_attempt
        FOREIGN KEY (attempt_id)
        REFERENCES task_attempts(id),

    CONSTRAINT execution_events_has_subject
        CHECK (
            workflow_id IS NOT NULL
            OR task_id IS NOT NULL
            OR attempt_id IS NOT NULL
            OR worker_id IS NOT NULL
        )
);


CREATE INDEX idx_execution_events_task
    ON execution_events(task_id, id);


CREATE INDEX idx_execution_events_workflow
    ON execution_events(workflow_id, id);


CREATE INDEX idx_execution_events_attempt
    ON execution_events(attempt_id, id);


CREATE INDEX idx_execution_events_created_at
    ON execution_events(created_at, id);
