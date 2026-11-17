--liquibase formatted sql

--changeset novelweb:translation-056-attempt-usage-ledger
CREATE TABLE translation_usage_ledger (
    attempt_id UUID PRIMARY KEY REFERENCES translation_attempts(id) ON DELETE CASCADE,
    provider_id VARCHAR(64) NOT NULL CHECK (provider_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    provider_model VARCHAR(128) NOT NULL
        CHECK (length(btrim(provider_model)) BETWEEN 1 AND 128),
    provider_request_id VARCHAR(256) NOT NULL
        CHECK (length(btrim(provider_request_id)) BETWEEN 1 AND 256),
    confirmed_units BIGINT NOT NULL CHECK (confirmed_units >= 0),
    billing_unit VARCHAR(24) NOT NULL
        CHECK (billing_unit IN ('CODE_POINT', 'TOKEN', 'REQUEST')),
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_translation_usage_ledger_confirmed_at
    ON translation_usage_ledger (confirmed_at DESC, attempt_id);
--rollback DROP INDEX ix_translation_usage_ledger_confirmed_at;
--rollback DROP TABLE translation_usage_ledger;

--changeset novelweb:translation-057-attempt-usage-ledger-guard splitStatements:false
CREATE FUNCTION guard_translation_usage_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_attempt translation_attempts%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO source_attempt
        FROM translation_attempts
        WHERE id = NEW.attempt_id;

        IF NOT FOUND
            OR source_attempt.outcome <> 'succeeded'
            OR source_attempt.completed_at IS NULL
            OR source_attempt.provider_id IS DISTINCT FROM NEW.provider_id
            OR source_attempt.provider_model IS DISTINCT FROM NEW.provider_model
            OR source_attempt.provider_request_id IS DISTINCT FROM NEW.provider_request_id
            OR source_attempt.billed_units IS DISTINCT FROM NEW.confirmed_units
            OR source_attempt.billed_billing_unit IS DISTINCT FROM NEW.billing_unit
        THEN
            RAISE EXCEPTION 'Translation usage must match a completed provider attempt'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Translation usage ledger is append-only'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        -- Account erasure and job retention may cascade-delete private attempt history.
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Translation usage ledger is append-only'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Translation usage ledger is append-only'
            USING ERRCODE = '55000';
    END IF;

    RETURN NULL;
END;
$$;
--rollback DROP FUNCTION guard_translation_usage_ledger_mutation();

--changeset novelweb:translation-058-attempt-usage-ledger-guards
CREATE TRIGGER trg_translation_usage_ledger_row_guard
    BEFORE INSERT OR UPDATE OR DELETE ON translation_usage_ledger
    FOR EACH ROW EXECUTE FUNCTION guard_translation_usage_ledger_mutation();
CREATE TRIGGER trg_translation_usage_ledger_truncate_guard
    BEFORE TRUNCATE ON translation_usage_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION guard_translation_usage_ledger_mutation();
--rollback DROP TRIGGER trg_translation_usage_ledger_truncate_guard ON translation_usage_ledger;
--rollback DROP TRIGGER trg_translation_usage_ledger_row_guard ON translation_usage_ledger;
