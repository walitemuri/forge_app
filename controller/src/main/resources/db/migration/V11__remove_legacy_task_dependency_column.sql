ALTER TABLE forge_tasks
    DROP CONSTRAINT IF EXISTS fk_forge_tasks_dependency;

ALTER TABLE forge_tasks
    DROP CONSTRAINT IF EXISTS forge_tasks_no_self_dependency;

DROP INDEX IF EXISTS idx_forge_tasks_depends_on_task_id;

ALTER TABLE forge_tasks
    DROP COLUMN IF EXISTS depends_on_task_id;
