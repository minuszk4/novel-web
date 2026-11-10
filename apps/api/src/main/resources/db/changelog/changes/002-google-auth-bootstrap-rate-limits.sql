--liquibase formatted sql

--changeset novelweb:identity-013-google-auth-bootstrap-rate-limits
CREATE TABLE google_auth_bootstrap_rate_limits (
    rate_limit_key_hash BYTEA PRIMARY KEY CHECK (octet_length(rate_limit_key_hash) = 32),
    available_tokens NUMERIC(8, 6) NOT NULL DEFAULT 10
        CHECK (available_tokens >= 0 AND available_tokens <= 10),
    refilled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_google_auth_bootstrap_rate_limits_last_seen
    ON google_auth_bootstrap_rate_limits (last_seen_at);
--rollback DROP TABLE google_auth_bootstrap_rate_limits;
