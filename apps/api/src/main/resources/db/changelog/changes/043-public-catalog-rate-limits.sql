--liquibase formatted sql

--changeset novelweb:security-043-public-catalog-rate-limits
CREATE TABLE public_catalog_rate_limits (
    rate_limit_key_hash BYTEA PRIMARY KEY CHECK (octet_length(rate_limit_key_hash) = 32),
    available_tokens NUMERIC(10, 6) NOT NULL DEFAULT 60
        CHECK (available_tokens >= 0 AND available_tokens <= 500),
    refilled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_public_catalog_rate_limits_last_seen
    ON public_catalog_rate_limits (last_seen_at, rate_limit_key_hash);
--rollback DROP TABLE public_catalog_rate_limits;
