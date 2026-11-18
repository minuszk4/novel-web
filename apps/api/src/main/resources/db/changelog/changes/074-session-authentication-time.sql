--liquibase formatted sql

--changeset novelweb:identity-044-session-authentication-time
ALTER TABLE sessions ADD COLUMN authenticated_at TIMESTAMPTZ;
UPDATE sessions SET authenticated_at = created_at WHERE authenticated_at IS NULL;
ALTER TABLE sessions ALTER COLUMN authenticated_at SET DEFAULT clock_timestamp();
ALTER TABLE sessions ALTER COLUMN authenticated_at SET NOT NULL;
--rollback ALTER TABLE sessions DROP COLUMN authenticated_at;
