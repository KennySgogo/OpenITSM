-- Run scripts/MigrateLegacySecrets.java before upgrading a database containing
-- encrypted account mail or settings. Abort instead of accepting unreadable data.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM notification_task WHERE template IN ('INVITE','RESET')
        AND sealed_payload LIKE '%.%')
        OR EXISTS (SELECT 1 FROM team_settings WHERE smtp_password ~ '^[A-Za-z0-9+/]{16}\.'
            OR model_api_key ~ '^[A-Za-z0-9+/]{16}\.') THEN
        RAISE EXCEPTION 'Legacy secrets require migration: see backend README';
    END IF;
END $$;

ALTER TABLE notification_task RENAME COLUMN sealed_payload TO delivery_token;

-- Retain historical technical records for recovery, outside the application schema.
DO $$
DECLARE archive_name text := CASE WHEN current_schema() = 'public'
    THEN 'openitsm_legacy_v17' ELSE current_schema() || '_legacy_v17' END;
BEGIN
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', archive_name);
    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', archive_name);
    EXECUTE format('ALTER TABLE idempotency_record SET SCHEMA %I', archive_name);
END $$;
