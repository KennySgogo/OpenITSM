ALTER TABLE ticket_type ADD COLUMN description text
    CHECK (description IS NULL OR (length(description) <= 500 AND length(btrim(description)) > 0));
