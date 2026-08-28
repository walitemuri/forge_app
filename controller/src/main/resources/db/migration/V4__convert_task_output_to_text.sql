ALTER TABLE forge_tasks
    ALTER COLUMN stdout TYPE TEXT
    USING (
        CASE
            WHEN stdout IS NULL THEN NULL
            ELSE convert_from(lo_get(stdout), 'UTF8')
        END
    );

ALTER TABLE forge_tasks
    ALTER COLUMN stderr TYPE TEXT
    USING (
        CASE
            WHEN stderr IS NULL THEN NULL
            ELSE convert_from(lo_get(stderr), 'UTF8')
        END
    );
