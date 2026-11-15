--liquibase formatted sql

--changeset novelweb:commerce-008-momo-test-ipn-settlement
ALTER TABLE credit_top_up_orders
    ADD COLUMN auto_capture BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN provider_transaction_id BIGINT,
    ADD CONSTRAINT ck_credit_top_up_provider_transaction_id
        CHECK (provider_transaction_id IS NULL OR provider_transaction_id > 0),
    ADD CONSTRAINT uq_credit_top_up_provider_environment_transaction_id
        UNIQUE (provider, environment, provider_transaction_id);

CREATE TABLE momo_test_ipn_events (
    event_hash CHAR(64) PRIMARY KEY
        CHECK (event_hash ~ '^[0-9a-f]{64}$'),
    provider_order_id VARCHAR(200) NOT NULL,
    provider_request_id VARCHAR(50) NOT NULL,
    provider_transaction_id BIGINT NOT NULL
        CHECK (provider_transaction_id >= 0),
    amount_vnd BIGINT NOT NULL CHECK (amount_vnd >= 0),
    result_code INTEGER NOT NULL,
    credit_top_up_order_id UUID
        REFERENCES credit_top_up_orders(id) ON DELETE CASCADE,
    processing_outcome VARCHAR(32) NOT NULL
        CHECK (processing_outcome IN (
            'SUCCEEDED', 'PENDING', 'FAILED', 'UNKNOWN', 'DUPLICATE',
            'ORDER_NOT_FOUND', 'ORDER_MISMATCH', 'TERMINAL_CONFLICT',
            'ACCOUNT_INACTIVE', 'TRANSACTION_CONFLICT')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_momo_test_ipn_provider_order_id
        CHECK (provider_order_id <> ''),
    CONSTRAINT ck_momo_test_ipn_provider_request_id
        CHECK (provider_request_id <> '')
);

CREATE INDEX ix_momo_test_ipn_order_recent
    ON momo_test_ipn_events(credit_top_up_order_id, received_at DESC);

--rollback DROP TABLE momo_test_ipn_events;
--rollback ALTER TABLE credit_top_up_orders DROP CONSTRAINT uq_credit_top_up_provider_environment_transaction_id;
--rollback ALTER TABLE credit_top_up_orders DROP CONSTRAINT ck_credit_top_up_provider_transaction_id;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN provider_transaction_id;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN auto_capture;
