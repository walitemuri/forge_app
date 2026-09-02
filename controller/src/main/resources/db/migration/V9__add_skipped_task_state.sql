ALTER TABLE forge_tasks
    DROP CONSTRAINT forge_tasks_status_check;

ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_status_check
    CHECK (
        status IN (
            'CREATED',
            'BLOCKED',
            'PENDING',
            'DISPATCHED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'LOST',
            'CANCELLED',
            'SKIPPED'
        )
    );