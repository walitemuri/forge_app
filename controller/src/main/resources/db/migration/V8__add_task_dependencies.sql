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
            'CANCELLED'
        )
    );


ALTER TABLE forge_tasks
    ADD COLUMN depends_on_task_id VARCHAR(255);


ALTER TABLE forge_tasks
    ADD CONSTRAINT fk_forge_tasks_dependency
    FOREIGN KEY (depends_on_task_id)
    REFERENCES forge_tasks(id);


ALTER TABLE forge_tasks
    ADD CONSTRAINT forge_tasks_no_self_dependency
    CHECK (
        depends_on_task_id IS NULL
        OR depends_on_task_id <> id
    );


CREATE INDEX idx_forge_tasks_depends_on_task_id
    ON forge_tasks(depends_on_task_id);
