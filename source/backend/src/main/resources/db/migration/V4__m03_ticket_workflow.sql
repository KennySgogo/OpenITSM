ALTER TABLE ticket
    ADD COLUMN priority text NOT NULL DEFAULT 'P3' CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    ADD COLUMN resolution_summary text,
    ADD COLUMN closed_at timestamptz,
    ADD COLUMN cycle_started_at timestamptz;

UPDATE ticket SET cycle_started_at = created_at;
ALTER TABLE ticket ALTER COLUMN cycle_started_at SET NOT NULL;
ALTER TABLE ticket ALTER COLUMN cycle_started_at SET DEFAULT CURRENT_TIMESTAMP;
