--liquibase formatted sql

--changeset novelweb:notifications-070-work-subscription-rate-limits
CREATE TABLE work_subscription_rate_limits (
    actor_user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    available_tokens NUMERIC(12, 6) NOT NULL CHECK (available_tokens >= 0 AND available_tokens <= 100),
    refilled_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CHECK (last_seen_at >= refilled_at)
);

CREATE INDEX ix_work_subscription_rate_limits_last_seen
    ON work_subscription_rate_limits (last_seen_at, actor_user_id);

--rollback DROP INDEX ix_work_subscription_rate_limits_last_seen;
--rollback DROP TABLE work_subscription_rate_limits;
