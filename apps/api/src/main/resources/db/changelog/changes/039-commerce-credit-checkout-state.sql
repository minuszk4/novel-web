--liquibase formatted sql

--changeset novelweb:commerce-007-credit-checkout-state
ALTER TABLE credit_top_up_orders
    ADD COLUMN checkout_url TEXT;

ALTER TABLE credit_top_up_orders
    ADD CONSTRAINT ck_credit_top_up_checkout_url_test_host
        CHECK (
            checkout_url IS NULL
            OR (
                length(checkout_url) <= 4096
                AND lower(checkout_url) ~ '^https://test-payment\.momo\.vn(:443)?/v2/gateway/pay\?[^#[:space:]]+$'
            )
        );

ALTER TABLE credit_top_up_orders
    ADD CONSTRAINT ck_credit_top_up_checkout_url_pending
        CHECK (checkout_url IS NULL OR status = 'PENDING');

--rollback ALTER TABLE credit_top_up_orders DROP CONSTRAINT ck_credit_top_up_checkout_url_pending;
--rollback ALTER TABLE credit_top_up_orders DROP CONSTRAINT ck_credit_top_up_checkout_url_test_host;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN checkout_url;
