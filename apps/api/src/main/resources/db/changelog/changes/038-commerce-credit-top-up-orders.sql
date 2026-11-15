--liquibase formatted sql

--changeset novelweb:commerce-006-credit-top-up-orders
CREATE TABLE credit_top_up_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL,
    pack_id VARCHAR(64) NOT NULL,
    pack_version INTEGER NOT NULL CHECK (pack_version > 0),
    provider VARCHAR(16) NOT NULL CHECK (provider = 'MOMO'),
    environment VARCHAR(8) NOT NULL CHECK (environment = 'TEST'),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'VND'),
    amount_vnd BIGINT NOT NULL CHECK (amount_vnd BETWEEN 1000 AND 50000000),
    paid_credits BIGINT NOT NULL CHECK (paid_credits > 0),
    bonus_credits BIGINT NOT NULL DEFAULT 0 CHECK (bonus_credits >= 0),
    paid_credit_expires_at TIMESTAMPTZ,
    bonus_credit_expires_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'INITIATING', 'PENDING', 'UNKNOWN',
                          'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_credit_top_up_order_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    CONSTRAINT ck_credit_top_up_bonus_expiry
        CHECK (bonus_credits > 0 OR bonus_credit_expires_at IS NULL),
    CONSTRAINT uq_credit_top_up_order_actor_idempotency
        UNIQUE (user_id, idempotency_key)
);
CREATE INDEX ix_credit_top_up_orders_user_recent
    ON credit_top_up_orders (user_id, created_at DESC, id DESC);
--rollback DROP TABLE credit_top_up_orders;
