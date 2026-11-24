--liquibase formatted sql

--changeset novelweb:privacy-002-account-privacy-preferences
CREATE TABLE account_privacy_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    analytics_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    content_provider_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

--rollback DROP TABLE account_privacy_preferences;
