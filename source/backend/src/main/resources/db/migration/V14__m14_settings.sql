CREATE TABLE team_settings (
    id uuid PRIMARY KEY CHECK (id = '00000000-0000-0000-0000-000000000014'),
    version bigint NOT NULL DEFAULT 0,
    team_name varchar(80) NOT NULL,
    smtp_host varchar(253) NOT NULL,
    smtp_port integer NOT NULL CHECK (smtp_port BETWEEN 1 AND 65535),
    smtp_username varchar(320) NOT NULL,
    smtp_from varchar(320) NOT NULL,
    smtp_security varchar(16) NOT NULL CHECK (smtp_security IN ('SSL','STARTTLS','NONE')),
    smtp_password text,
    model_base_url varchar(2048) NOT NULL,
    model_name varchar(200) NOT NULL,
    model_api_key text,
    ai_enabled boolean NOT NULL DEFAULT false
);

-- Status is independent of the configuration version; stale tests stay tied to their tested version.
CREATE TABLE ai_connection_status (
    kind varchar(16) PRIMARY KEY CHECK (kind IN ('TEST','CALL')),
    config_version bigint NOT NULL,
    status varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    attempt_id uuid NOT NULL
);
