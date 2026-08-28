CREATE TABLE task_attempts (
    id VARCHAR(36) PRIMARY KEY,

    task_id VARCHAR(36) NOT NULL,

    attempt_number INTEGER NOT NULL,

    worker_id VARCHAR(255),

    status VARCHAR(32) NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    started_at TIMESTAMP WITH TIME ZONE,

    finished_at TIMESTAMP WITH TIME ZONE,

    exit_code INTEGER,

    stdout TEXT,

    stderr TEXT,

    CONSTRAINT fk_task_attempt_task
        FOREIGN KEY (task_id)
        REFERENCES forge_tasks(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_task_attempt_number
        UNIQUE (task_id, attempt_number),

    CONSTRAINT task_attempt_status_check
        CHECK (
            status IN (
                'CREATED',
                'DISPATCHED',
                'RUNNING',
                'SUCCEEDED',
                'FAILED',
                'LOST'
            )
        )
);

CREATE INDEX idx_task_attempt_task_id
    ON task_attempts(task_id);

CREATE INDEX idx_task_attempt_status
    ON task_attempts(status);
