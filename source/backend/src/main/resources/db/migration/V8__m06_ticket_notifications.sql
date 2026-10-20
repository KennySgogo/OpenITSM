-- Reuse the existing durable delivery queue; account-token payloads stay encrypted.
ALTER TABLE notification_task DROP CONSTRAINT notification_task_template_check;
ALTER TABLE notification_task
    ADD CONSTRAINT notification_task_template_check CHECK (template IN ('INVITE', 'RESET', 'TICKET')),
    ADD COLUMN ticket_id uuid REFERENCES ticket(id),
    ADD COLUMN ticket_action varchar(32),
    ADD CONSTRAINT notification_task_ticket_fields_check CHECK (
        (template = 'TICKET' AND ticket_id IS NOT NULL AND ticket_action IS NOT NULL
            AND sealed_payload = '' AND token_generation = 0)
        OR (template IN ('INVITE', 'RESET') AND ticket_id IS NULL AND ticket_action IS NULL)
    );
