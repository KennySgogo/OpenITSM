ALTER TABLE ai_connection_status ADD COLUMN output_mode varchar(16)
    CHECK (output_mode IS NULL OR (kind = 'TEST' AND status = 'SUCCESS' AND output_mode IN ('JSON_SCHEMA','TOOL_CALL')));
