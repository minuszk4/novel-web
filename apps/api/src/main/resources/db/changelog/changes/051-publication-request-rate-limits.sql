--liquibase formatted sql

--changeset novelweb:publication-059-request-rate-limits
CREATE TABLE publication_request_rate_limits (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    available_tokens NUMERIC(12, 6) NOT NULL CHECK (available_tokens >= 0 AND available_tokens <= 100),
    refilled_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CHECK (last_seen_at >= refilled_at)
);

CREATE INDEX ix_publication_request_rate_limits_last_seen
    ON publication_request_rate_limits (last_seen_at, user_id);
--rollback DROP INDEX ix_publication_request_rate_limits_last_seen;
--rollback DROP TABLE publication_request_rate_limits;
