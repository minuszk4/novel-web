--liquibase formatted sql

--changeset novelweb:imports-060-actor-rate-limits
CREATE TABLE import_actor_rate_limits (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action VARCHAR(16) NOT NULL CHECK (action IN ('CREATE', 'UPLOAD')),
    available_tokens NUMERIC(12, 6) NOT NULL CHECK (available_tokens >= 0),
    refilled_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, action)
);

CREATE INDEX ix_import_actor_rate_limits_last_seen
    ON import_actor_rate_limits (last_seen_at, user_id, action);
--rollback DROP TABLE import_actor_rate_limits;
