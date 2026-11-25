--liquibase formatted sql

--changeset novelweb:commerce-083-vnpay-test-ipn
ALTER TABLE credit_top_up_orders
    DROP CONSTRAINT credit_top_up_orders_provider_check,
    ADD CONSTRAINT ck_credit_top_up_provider
        CHECK (provider IN ('MOMO', 'VNPAY'));

ALTER TABLE credit_top_up_orders
    DROP CONSTRAINT ck_credit_top_up_checkout_url_test_host,
    ADD CONSTRAINT ck_credit_top_up_checkout_url_test_host
        CHECK (
            checkout_url IS NULL
            OR (
                length(checkout_url) <= 4096
                AND lower(checkout_url) ~
                    '^https://(test-payment\.momo\.vn(:443)?/v2/gateway/pay|sandbox\.vnpayment\.vn(:443)?/paymentv2/vpcpay\.html)\?[^#[:space:]]+$'
            )
        );

CREATE TABLE vnpay_test_ipn_events (
    event_hash CHAR(64) PRIMARY KEY
        CHECK (event_hash ~ '^[0-9a-f]{64}$'),
    txn_ref VARCHAR(100) NOT NULL,
    transaction_no BIGINT NOT NULL CHECK (transaction_no >= 0),
    amount_vnd BIGINT NOT NULL CHECK (amount_vnd >= 0),
    response_code VARCHAR(2) NOT NULL CHECK (response_code ~ '^[0-9]{2}$'),
    transaction_status VARCHAR(2) NOT NULL CHECK (transaction_status ~ '^[0-9]{2}$'),
    credit_top_up_order_id UUID
        REFERENCES credit_top_up_orders(id) ON DELETE CASCADE,
    processing_outcome VARCHAR(32) NOT NULL
        CHECK (processing_outcome IN (
            'SUCCEEDED', 'PENDING', 'FAILED', 'UNKNOWN', 'DUPLICATE',
            'ORDER_NOT_FOUND', 'ORDER_MISMATCH', 'TERMINAL_CONFLICT',
            'ACCOUNT_INACTIVE', 'TRANSACTION_CONFLICT')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_vnpay_test_ipn_txn_ref CHECK (txn_ref <> '')
);

CREATE INDEX ix_vnpay_test_ipn_order_recent
    ON vnpay_test_ipn_events(credit_top_up_order_id, received_at DESC);

--rollback DROP TABLE vnpay_test_ipn_events;
--rollback ALTER TABLE credit_top_up_orders DROP CONSTRAINT ck_credit_top_up_provider;
--rollback ALTER TABLE credit_top_up_orders ADD CONSTRAINT credit_top_up_orders_provider_check CHECK (provider = 'MOMO');
