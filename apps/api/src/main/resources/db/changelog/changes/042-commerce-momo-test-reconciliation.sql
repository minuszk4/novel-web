--liquibase formatted sql

--changeset novelweb:commerce-009-momo-test-reconciliation
ALTER TABLE credit_top_up_orders
    ADD COLUMN reconciliation_attempt_count SMALLINT NOT NULL DEFAULT 0
        CHECK (reconciliation_attempt_count BETWEEN 0 AND 8),
    ADD COLUMN next_reconciliation_at TIMESTAMPTZ,
    ADD COLUMN last_reconciliation_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_lease_until TIMESTAMPTZ,
    ADD COLUMN reconciliation_review_required_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_review_code VARCHAR(32),
    ADD CONSTRAINT ck_credit_top_up_reconciliation_review_pair
        CHECK ((reconciliation_review_required_at IS NULL) = (reconciliation_review_code IS NULL)),
    ADD CONSTRAINT ck_credit_top_up_reconciliation_review_code
        CHECK (reconciliation_review_code IS NULL OR reconciliation_review_code IN (
            'ATTEMPTS_EXHAUSTED', 'QUERY_INVALID_RESPONSE', 'QUERY_ORDER_MISMATCH',
            'ACCOUNT_INACTIVE', 'TERMINAL_STATUS_CONFLICT', 'TRANSACTION_ID_REUSED'));

CREATE INDEX ix_credit_top_up_reconciliation_due
    ON credit_top_up_orders (next_reconciliation_at, updated_at, id)
    WHERE status IN ('PENDING', 'UNKNOWN', 'INITIATING')
      AND reconciliation_review_required_at IS NULL
      AND reconciliation_attempt_count < 8;

CREATE TABLE momo_test_reconciliation_events (
    query_request_id UUID PRIMARY KEY,
    credit_top_up_order_id UUID NOT NULL REFERENCES credit_top_up_orders(id) ON DELETE CASCADE,
    attempt_number SMALLINT NOT NULL CHECK (attempt_number BETWEEN 1 AND 8),
    outcome VARCHAR(16) NOT NULL
        CHECK (outcome IN ('SUCCEEDED', 'PENDING', 'FAILED', 'UNKNOWN', 'QUARANTINED', 'STALE_IGNORED')),
    provider_transaction_id BIGINT CHECK (provider_transaction_id IS NULL OR provider_transaction_id > 0),
    review_code VARCHAR(32)
        CHECK (review_code IS NULL OR review_code IN (
            'ATTEMPTS_EXHAUSTED', 'QUERY_INVALID_RESPONSE', 'QUERY_ORDER_MISMATCH',
            'ACCOUNT_INACTIVE', 'TERMINAL_STATUS_CONFLICT', 'TRANSACTION_ID_REUSED')),
    queried_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_momo_test_reconciliation_review_outcome
        CHECK (review_code IS NULL OR outcome = 'QUARANTINED'
               OR (review_code = 'ATTEMPTS_EXHAUSTED' AND outcome IN ('PENDING', 'UNKNOWN')))
);

CREATE INDEX ix_momo_test_reconciliation_order_recent
    ON momo_test_reconciliation_events (credit_top_up_order_id, queried_at DESC);

--rollback DROP INDEX ix_momo_test_reconciliation_order_recent;
--rollback DROP TABLE momo_test_reconciliation_events;
--rollback DROP INDEX ix_credit_top_up_reconciliation_due;
--rollback ALTER TABLE credit_top_up_orders DROP CONSTRAINT ck_credit_top_up_reconciliation_review_code;
--rollback ALTER TABLE credit_top_up_orders DROP CONSTRAINT ck_credit_top_up_reconciliation_review_pair;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN reconciliation_review_code;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN reconciliation_review_required_at;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN last_reconciliation_at;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN reconciliation_lease_until;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN next_reconciliation_at;
--rollback ALTER TABLE credit_top_up_orders DROP COLUMN reconciliation_attempt_count;

--changeset novelweb:commerce-010-momo-test-reconciliation-events-immutable splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION guard_momo_test_reconciliation_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Account erasure may cascade-delete an order and its private reconciliation history.
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'MoMo TEST reconciliation events are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_momo_test_reconciliation_event_immutable
    BEFORE UPDATE OR DELETE ON momo_test_reconciliation_events
    FOR EACH ROW EXECUTE FUNCTION guard_momo_test_reconciliation_event_mutation();

--rollback DROP TRIGGER trg_momo_test_reconciliation_event_immutable ON momo_test_reconciliation_events;
--rollback DROP FUNCTION guard_momo_test_reconciliation_event_mutation();
