CREATE TABLE ticket_attachment (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL REFERENCES ticket(id),
    uploader_id uuid NOT NULL REFERENCES app_user(id),
    file_name text NOT NULL,
    size bigint NOT NULL CHECK (size BETWEEN 1 AND 10485760),
    content_hash char(64) NOT NULL,
    visibility text NOT NULL CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
    created_at timestamptz NOT NULL
);
CREATE INDEX ticket_attachment_timeline ON ticket_attachment(ticket_id, created_at, id);
