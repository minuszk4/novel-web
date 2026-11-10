--liquibase formatted sql

--changeset novelweb:identity-001-users
CREATE TABLE users (
    id UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL CHECK (length(btrim(display_name)) > 0),
    contact_email VARCHAR(320),
    email_verified_at TIMESTAMPTZ,
    avatar_url TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'pending', 'suspended', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    CHECK ((status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL))
);
--rollback DROP TABLE users;

--changeset novelweb:identity-002-verified-email-index
CREATE UNIQUE INDEX uq_users_verified_contact_email
    ON users (lower(contact_email))
    WHERE contact_email IS NOT NULL
      AND email_verified_at IS NOT NULL
      AND status <> 'deleted';
--rollback DROP INDEX uq_users_verified_contact_email;

--changeset novelweb:identity-003-external-identities
CREATE TABLE external_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    provider VARCHAR(32) NOT NULL CHECK (provider = 'google'),
    issuer VARCHAR(256) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    provider_email VARCHAR(320),
    provider_email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ,
    CONSTRAINT uq_external_identity_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT uq_external_identity_user_provider UNIQUE (user_id, provider),
    CHECK (NOT provider_email_verified OR COALESCE(provider_email IS NOT NULL, FALSE))
);
--rollback DROP TABLE external_identities;

--changeset novelweb:identity-005-sessions
CREATE TABLE sessions (
    id_hash BYTEA PRIMARY KEY CHECK (octet_length(id_hash) = 32),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    user_agent_summary VARCHAR(512),
    ip_prefix_hash BYTEA CHECK (ip_prefix_hash IS NULL OR octet_length(ip_prefix_hash) = 32),
    CHECK (expires_at > created_at),
    CHECK (last_seen_at >= created_at)
);
--rollback DROP TABLE sessions;

--changeset novelweb:identity-006-active-session-index
CREATE INDEX ix_sessions_active_user_expiry
    ON sessions (user_id, expires_at)
    WHERE revoked_at IS NULL;
--rollback DROP INDEX ix_sessions_active_user_expiry;

--changeset novelweb:identity-007-google-auth-attempts
CREATE TABLE google_auth_attempts (
    id UUID PRIMARY KEY,
    nonce_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(nonce_hash) = 32),
    safe_return_path VARCHAR(2048) NOT NULL DEFAULT '/app/home'
        CHECK (safe_return_path LIKE '/%'
            AND safe_return_path NOT LIKE '//%'
            AND position(chr(92) IN safe_return_path) = 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
--rollback DROP TABLE google_auth_attempts;

--changeset novelweb:identity-008-active-auth-attempt-expiry-index
CREATE INDEX ix_google_auth_attempts_pending_expiry
    ON google_auth_attempts (expires_at)
    WHERE consumed_at IS NULL;
--rollback DROP INDEX ix_google_auth_attempts_pending_expiry;

--changeset novelweb:identity-009-policy-acceptances
CREATE TABLE policy_acceptances (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    policy_type VARCHAR(32) NOT NULL
        CHECK (policy_type IN ('TERMS', 'PRIVACY_NOTICE')),
    version VARCHAR(64) NOT NULL,
    decision VARCHAR(32) NOT NULL
        CHECK (decision IN ('TERMS_ACCEPTED', 'PRIVACY_NOTICE_ACKNOWLEDGED')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(32) NOT NULL,
    CONSTRAINT uq_policy_acceptance_user_type_version
        UNIQUE (user_id, policy_type, version),
    CHECK ((policy_type = 'TERMS' AND decision = 'TERMS_ACCEPTED')
        OR (policy_type = 'PRIVACY_NOTICE' AND decision = 'PRIVACY_NOTICE_ACKNOWLEDGED'))
);
--rollback DROP TABLE policy_acceptances;

--changeset novelweb:identity-010-policy-acceptance-history-index
CREATE INDEX ix_policy_acceptances_user_type_recorded
    ON policy_acceptances (user_id, policy_type, recorded_at DESC);
--rollback DROP INDEX ix_policy_acceptances_user_type_recorded;

--changeset novelweb:identity-011-auth-events
CREATE TABLE auth_events (
    event_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    provider VARCHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--rollback DROP TABLE auth_events;

--changeset novelweb:identity-012-auth-event-user-index
CREATE INDEX ix_auth_events_user_occurred
    ON auth_events (user_id, occurred_at DESC)
    WHERE user_id IS NOT NULL;
--rollback DROP INDEX ix_auth_events_user_occurred;
