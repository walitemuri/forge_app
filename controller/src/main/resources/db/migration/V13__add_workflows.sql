CREATE TABLE forge_workflows (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);


ALTER TABLE forge_tasks
    ADD COLUMN workflow_id VARCHAR(255);


ALTER TABLE forge_tasks
    ADD COLUMN workflow_task_key VARCHAR(255);


ALTER TABLE forge_tasks
    ADD CONSTRAINT fk_forge_tasks_workflow
    FOREIGN KEY (workflow_id)
    REFERENCES forge_workflows(id);


ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_workflow_membership_valid
    CHECK (
        (
            workflow_id IS NULL
            AND workflow_task_key IS NULL
        )
        OR
        (
            workflow_id IS NOT NULL
            AND workflow_task_key IS NOT NULL
        )
    );


ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_workflow_task_key_unique
    UNIQUE (workflow_id, workflow_task_key);


CREATE INDEX idx_forge_tasks_workflow_id
    ON forge_tasks(workflow_id);
