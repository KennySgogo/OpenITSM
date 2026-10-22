-- Retain historical output_mode values without using them in current requests.
-- A previous successful mode must not constrain ordinary test/call status updates.
ALTER TABLE ai_connection_status DROP CONSTRAINT ai_connection_status_check;
