--liquibase formatted sql

--changeset novelweb:identity-013-session-revoke-confirmations
CREATE TABLE session_revoke_confirmations (
    token_hash BYTEA PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    session_id_hash BYTEA NOT NULL REFERENCES sessions(id_hash) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
--rollback DROP TABLE session_revoke_confirmations;

--changeset novelweb:identity-014-session-revoke-confirmation-pending-session-index
CREATE UNIQUE INDEX uq_session_revoke_confirmation_pending_session
    ON session_revoke_confirmations (session_id_hash)
    WHERE consumed_at IS NULL;
--rollback DROP INDEX uq_session_revoke_confirmation_pending_session;

--changeset novelweb:identity-015-session-revoke-confirmation-expiry-index
CREATE INDEX ix_session_revoke_confirmation_expiry
    ON session_revoke_confirmations (expires_at);
--rollback DROP INDEX ix_session_revoke_confirmation_expiry;
