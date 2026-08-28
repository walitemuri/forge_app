ALTER TABLE forge_tasks
    ADD COLUMN timeout_seconds INTEGER NOT NULL DEFAULT 0;

ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_timeout_seconds_check
    CHECK (
        timeout_seconds >= 0
        AND timeout_seconds <= 86400
    );
