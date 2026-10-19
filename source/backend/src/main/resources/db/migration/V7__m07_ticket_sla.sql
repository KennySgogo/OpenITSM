-- Historical replies lack the author's role at the time of writing.
-- Preserve existing tickets without claiming complete historical SLA data.
ALTER TABLE ticket
    ADD COLUMN sla_tracked boolean NOT NULL DEFAULT false,
    ADD COLUMN first_responded_at timestamptz,
    ADD COLUMN resolved_at timestamptz;

ALTER TABLE ticket ALTER COLUMN sla_tracked SET DEFAULT true;
