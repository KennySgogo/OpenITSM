CREATE TABLE ticket_type (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    creator_id uuid REFERENCES app_user(id),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0
);
ALTER TABLE ticket ADD COLUMN business_type_id uuid REFERENCES ticket_type(id);
INSERT INTO ticket_type(id, name)
SELECT gen_random_uuid(), business_type FROM ticket GROUP BY business_type;
UPDATE ticket t SET business_type_id = bt.id FROM ticket_type bt WHERE bt.name = t.business_type;
ALTER TABLE ticket ALTER COLUMN business_type_id SET NOT NULL;
CREATE INDEX ticket_type_reference ON ticket(business_type_id);
