ALTER TABLE task_attempts
    ADD COLUMN worker_session_id VARCHAR(255);

CREATE INDEX idx_task_attempts_worker_session
    ON task_attempts(worker_id, worker_session_id);
