CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    email_normalized varchar(254) NOT NULL UNIQUE,
    name varchar(320) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('EMPLOYEE', 'ENGINEER', 'ADMIN')),
    status varchar(16) NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    password_hash text,
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    locked_until timestamptz,
    auth_version bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status <> 'ACTIVE' OR password_hash IS NOT NULL)
);
CREATE TABLE auth_session (
    token_hash char(64) PRIMARY KEY,
    user_id uuid REFERENCES app_user(id),
    auth_version bigint,
    created_at timestamptz NOT NULL,
    last_user_activity_at timestamptz NOT NULL,
    last_activity_ping_at timestamptz,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    csrf_hash char(64) NOT NULL
);
CREATE INDEX auth_session_user ON auth_session(user_id);
CREATE TABLE account_token (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    purpose varchar(8) NOT NULL CHECK (purpose IN ('INVITE', 'RESET')),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    generation bigint NOT NULL,
    UNIQUE (user_id, purpose, generation)
);
CREATE TABLE auth_rate_bucket (
    scope varchar(40) NOT NULL,
    key_hash char(64) NOT NULL,
    window_start timestamptz NOT NULL,
    count integer NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (scope, key_hash, window_start)
);
CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    actor_id uuid REFERENCES app_user(id),
    action varchar(64) NOT NULL,
    object_type varchar(32) NOT NULL,
    object_id uuid NOT NULL,
    before_after jsonb NOT NULL,
    reason text,
    outcome varchar(16) NOT NULL,
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE TABLE notification_task (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    recipient_id uuid NOT NULL REFERENCES app_user(id),
    channel varchar(16) NOT NULL DEFAULT 'EMAIL',
    template varchar(16) NOT NULL CHECK (template IN ('INVITE', 'RESET')),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_until timestamptz,
    error_code varchar(64),
    sealed_payload text NOT NULL,
    token_generation bigint NOT NULL,
    UNIQUE (event_id, recipient_id, channel)
);
CREATE TABLE idempotency_record (
    actor_id uuid NOT NULL REFERENCES app_user(id),
    operation text NOT NULL,
    key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('SUCCESS')),
    resource_id uuid NOT NULL,
    accepted_version bigint NOT NULL,
    response_status integer NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, operation, key)
);
CREATE TABLE account_bootstrap (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    completed_at timestamptz NOT NULL
);
