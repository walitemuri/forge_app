CREATE TABLE worker_authorities (
    worker_id VARCHAR(255) PRIMARY KEY,

    session_id VARCHAR(255) NOT NULL,

    updated_at TIMESTAMPTZ NOT NULL
        DEFAULT NOW()
);
