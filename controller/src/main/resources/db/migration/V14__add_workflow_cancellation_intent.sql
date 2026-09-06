ALTER TABLE forge_workflows
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
