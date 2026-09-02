CREATE TABLE task_dependencies (
    task_id VARCHAR(255) NOT NULL,
    depends_on_task_id VARCHAR(255) NOT NULL,

    CONSTRAINT pk_task_dependencies
        PRIMARY KEY (task_id, depends_on_task_id),

    CONSTRAINT fk_task_dependencies_task
        FOREIGN KEY (task_id)
        REFERENCES forge_tasks(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_task_dependencies_dependency
        FOREIGN KEY (depends_on_task_id)
        REFERENCES forge_tasks(id)
        ON DELETE CASCADE,

    CONSTRAINT task_dependencies_no_self_dependency
        CHECK (task_id <> depends_on_task_id)
);

CREATE INDEX idx_task_dependencies_dependency
    ON task_dependencies(depends_on_task_id);

-- Preserve dependencies created by the old
-- single-dependency implementation.
INSERT INTO task_dependencies (
    task_id,
    depends_on_task_id
)
SELECT
    id,
    depends_on_task_id
FROM forge_tasks
WHERE depends_on_task_id IS NOT NULL
ON CONFLICT DO NOTHING;
