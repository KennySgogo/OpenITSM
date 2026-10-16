CREATE SEQUENCE ticket_number_seq;

CREATE TABLE ticket (
    id uuid PRIMARY KEY,
    number text NOT NULL UNIQUE DEFAULT ('ITSM-' || nextval('ticket_number_seq')),
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    description text NOT NULL CHECK (length(btrim(description)) > 0),
    business_type text NOT NULL CHECK (length(btrim(business_type)) > 0),
    impact_scope text NOT NULL CHECK (length(btrim(impact_scope)) > 0),
    requester_id uuid NOT NULL REFERENCES app_user(id),
    assignee_id uuid REFERENCES app_user(id),
    status text NOT NULL DEFAULT 'NEW',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
