--liquibase formatted sql

--changeset novelweb:commerce-001-credit-ledger
CREATE TABLE credit_ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entry_type VARCHAR(24) NOT NULL
        CHECK (entry_type IN ('TOP_UP', 'BONUS_GRANT', 'UNLOCK_DEBIT', 'ADJUSTMENT', 'REVERSAL')),
    bucket VARCHAR(8) NOT NULL CHECK (bucket IN ('PAID', 'BONUS')),
    amount_delta BIGINT NOT NULL CHECK (amount_delta <> 0),
    expires_at TIMESTAMPTZ,
    source_type VARCHAR(32) NOT NULL
        CHECK (source_type IN ('PAYMENT_ORDER', 'CHAPTER_UNLOCK', 'OPERATOR', 'REVERSAL')),
    source_id UUID NOT NULL,
    reversal_of_entry_id UUID UNIQUE
        REFERENCES credit_ledger_entries(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_credit_ledger_bonus_expiry
        CHECK (bucket = 'BONUS' OR expires_at IS NULL),
    CONSTRAINT ck_credit_ledger_reversal_type
        CHECK ((entry_type = 'REVERSAL') = (reversal_of_entry_id IS NOT NULL)),
    CONSTRAINT ck_credit_ledger_entry_sign
        CHECK (
            (entry_type = 'TOP_UP' AND bucket = 'PAID' AND amount_delta > 0)
            OR (entry_type = 'BONUS_GRANT' AND bucket = 'BONUS' AND amount_delta > 0)
            OR (entry_type = 'UNLOCK_DEBIT' AND amount_delta < 0)
            OR entry_type IN ('ADJUSTMENT', 'REVERSAL')
        )
);

CREATE UNIQUE INDEX uq_credit_ledger_source_operation
    ON credit_ledger_entries (
        user_id, source_type, source_id, entry_type, bucket,
        COALESCE(expires_at, 'infinity'::timestamptz)
    );
CREATE INDEX ix_credit_ledger_user_recent
    ON credit_ledger_entries (user_id, created_at DESC, id DESC);
CREATE INDEX ix_credit_ledger_source
    ON credit_ledger_entries (source_type, source_id);
--rollback DROP TABLE credit_ledger_entries;

--changeset novelweb:commerce-002-credit-balance-projection
CREATE TABLE credit_balance_projection (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bucket VARCHAR(8) NOT NULL CHECK (bucket IN ('PAID', 'BONUS')),
    expires_at TIMESTAMPTZ,
    expiry_key TIMESTAMPTZ GENERATED ALWAYS AS
        (COALESCE(expires_at, 'infinity'::timestamptz)) STORED,
    balance BIGINT NOT NULL CHECK (balance >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_credit_projection_bonus_expiry
        CHECK (bucket = 'BONUS' OR expires_at IS NULL),
    CONSTRAINT uq_credit_projection_bucket_expiry
        UNIQUE (user_id, bucket, expiry_key)
);
CREATE INDEX ix_credit_projection_available
    ON credit_balance_projection (user_id, bucket, expires_at);
--rollback DROP TABLE credit_balance_projection;

--changeset novelweb:commerce-003-ledger-append-only splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION guard_credit_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Credit ledger entries are append-only'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- Account erasure may cascade-delete its private ledger; direct edits stay forbidden.
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Credit ledger entries are append-only'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_credit_ledger_append_only
    BEFORE UPDATE OR DELETE ON credit_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION guard_credit_ledger_mutation();
--rollback DROP TRIGGER trg_credit_ledger_append_only ON credit_ledger_entries;
--rollback DROP FUNCTION guard_credit_ledger_mutation();

--changeset novelweb:commerce-004-credit-balance-projection-write splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION apply_credit_ledger_projection()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    actor_active BOOLEAN;
BEGIN
    SELECT status = 'active'
    INTO actor_active
    FROM users
    WHERE id = NEW.user_id
    FOR UPDATE;
    IF NOT FOUND OR actor_active IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Credit ledger requires an active account'
            USING ERRCODE = '55000';
    END IF;

    UPDATE credit_balance_projection
    SET balance = balance + NEW.amount_delta,
        updated_at = clock_timestamp()
    WHERE user_id = NEW.user_id
      AND bucket = NEW.bucket
      AND expires_at IS NOT DISTINCT FROM NEW.expires_at;
    IF NOT FOUND THEN
        INSERT INTO credit_balance_projection (user_id, bucket, expires_at, balance, updated_at)
        VALUES (NEW.user_id, NEW.bucket, NEW.expires_at, NEW.amount_delta, clock_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_credit_balance_projection_write
    AFTER INSERT ON credit_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION apply_credit_ledger_projection();
--rollback DROP TRIGGER trg_credit_balance_projection_write ON credit_ledger_entries;
--rollback DROP FUNCTION apply_credit_ledger_projection();

--changeset novelweb:commerce-005-credit-balance-projection-rebuild splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION rebuild_credit_balance_projection(target_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM id
    FROM users
    WHERE id = target_user_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cannot rebuild credit balance for an unknown account'
            USING ERRCODE = '23503';
    END IF;

    DELETE FROM credit_balance_projection WHERE user_id = target_user_id;

    INSERT INTO credit_balance_projection (user_id, bucket, expires_at, balance, updated_at)
    SELECT user_id,
           bucket,
           expires_at,
           SUM(amount_delta)::BIGINT,
           clock_timestamp()
    FROM credit_ledger_entries
    WHERE user_id = target_user_id
    GROUP BY user_id, bucket, expires_at
    HAVING SUM(amount_delta) <> 0;
END;
$$;
--rollback DROP FUNCTION rebuild_credit_balance_projection(UUID);
