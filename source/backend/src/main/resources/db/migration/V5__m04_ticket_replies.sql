CREATE TABLE ticket_reply (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL REFERENCES ticket(id),
    author_id uuid NOT NULL REFERENCES app_user(id),
    content text NOT NULL CHECK (length(btrim(content)) > 0),
    visibility text NOT NULL CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
    created_at timestamptz NOT NULL
);
CREATE INDEX ticket_reply_timeline ON ticket_reply(ticket_id, created_at, id);
CREATE INDEX audit_event_ticket_timeline ON audit_event(object_id, created_at, id) WHERE object_type = 'TICKET';
