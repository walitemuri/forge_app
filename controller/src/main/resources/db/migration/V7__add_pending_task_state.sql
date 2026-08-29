ALTER TABLE forge_tasks
    DROP CONSTRAINT forge_tasks_status_check;


ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_status_check
    CHECK (
        status IN (
            'CREATED',
            'PENDING',
            'DISPATCHED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'LOST',
            'CANCELLED'
        )
    );


CREATE INDEX idx_forge_tasks_status_created_at
    ON forge_tasks(status, created_at);