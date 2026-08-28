ALTER TABLE forge_tasks
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 1;

ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_max_attempts_check
    CHECK (max_attempts >= 1 AND max_attempts <= 10);
