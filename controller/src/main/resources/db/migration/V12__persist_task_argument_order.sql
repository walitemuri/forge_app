ALTER TABLE forge_task_arguments
    ADD COLUMN argument_index INTEGER;


-- Existing rows never had an explicit ordering.
-- Preserve their current physical order as a deterministic
-- starting point for the new ordered representation.
WITH ordered_arguments AS (
    SELECT
        ctid,
        (
            ROW_NUMBER() OVER (
                PARTITION BY task_id
                ORDER BY ctid
            ) - 1
        )::INTEGER AS argument_index
    FROM forge_task_arguments
)
UPDATE forge_task_arguments AS arguments
SET argument_index =
        ordered_arguments.argument_index
FROM ordered_arguments
WHERE arguments.ctid =
        ordered_arguments.ctid;


ALTER TABLE forge_task_arguments
    ALTER COLUMN argument_index
    SET NOT NULL;


ALTER TABLE forge_task_arguments
    ADD CONSTRAINT forge_task_arguments_index_nonnegative
    CHECK (argument_index >= 0);


ALTER TABLE forge_task_arguments
    ADD CONSTRAINT forge_task_arguments_unique_index
    UNIQUE (task_id, argument_index);
