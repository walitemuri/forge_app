CREATE TABLE retired_worker_sessions (
    id VARCHAR(36) PRIMARY KEY,

    worker_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,

    retired_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT retired_worker_sessions_unique_session
        UNIQUE (worker_id, session_id)
);

CREATE INDEX idx_retired_worker_sessions_worker
    ON retired_worker_sessions(worker_id);
