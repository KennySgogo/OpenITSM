ALTER TABLE ticket ADD COLUMN rejection_reason text, ADD COLUMN rejected_at timestamptz;
ALTER TABLE ticket ADD CONSTRAINT ticket_rejection_consistency CHECK (
    (status = 'REJECTED' AND rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0 AND rejected_at IS NOT NULL AND resolved_at IS NULL)
    OR (status <> 'REJECTED' AND rejection_reason IS NULL AND rejected_at IS NULL)
);
