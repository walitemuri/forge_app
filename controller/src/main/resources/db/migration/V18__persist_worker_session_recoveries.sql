CREATE TABLE worker_session_recoveries (
    id VARCHAR(36) PRIMARY KEY,

    worker_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,

    recover_after TIMESTAMPTZ NOT NULL,

    CONSTRAINT worker_session_recoveries_unique_owner
        UNIQUE (worker_id, session_id)
);


CREATE INDEX idx_worker_session_recoveries_due
    ON worker_session_recoveries(recover_after);
