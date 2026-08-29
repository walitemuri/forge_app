ALTER TABLE forge_tasks
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;


ALTER TABLE forge_tasks
    DROP CONSTRAINT forge_tasks_status_check;


ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_status_check
    CHECK (
        status IN (
            'CREATED',
            'DISPATCHED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'LOST',
            'CANCELLED'
        )
    );


ALTER TABLE task_attempts
    DROP CONSTRAINT task_attempt_status_check;


ALTER TABLE task_attempts
    ADD CONSTRAINT task_attempt_status_check
    CHECK (
        status IN (
            'CREATED',
            'DISPATCHED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'LOST',
            'CANCELLED'
        )
    );
