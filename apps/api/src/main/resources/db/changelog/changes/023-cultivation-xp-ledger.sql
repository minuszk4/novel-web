--liquibase formatted sql

--changeset novelweb:cultivation-004-xp-ledger
CREATE UNIQUE INDEX uq_cultivation_rank_catalog_policy
    ON cultivation_rank_catalogs (version, policy_version);

CREATE TABLE cultivation_xp_ledger (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_session_id UUID NOT NULL,
    active_minute_index INTEGER NOT NULL CHECK (active_minute_index >= 0),
    activity_at TIMESTAMPTZ NOT NULL,
    activity_date DATE GENERATED ALWAYS AS ((activity_at AT TIME ZONE 'UTC')::date) STORED,
    xp_delta SMALLINT NOT NULL CHECK (xp_delta > 0),
    catalog_version VARCHAR(32) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_cultivation_xp_ledger_catalog_policy
        FOREIGN KEY (catalog_version, policy_version)
        REFERENCES cultivation_rank_catalogs (version, policy_version) ON DELETE RESTRICT,
    CONSTRAINT uq_cultivation_xp_ledger_session_minute
        UNIQUE (source_session_id, active_minute_index)
);

CREATE INDEX ix_cultivation_xp_ledger_user_day
    ON cultivation_xp_ledger (user_id, activity_date);
--rollback DROP TABLE cultivation_xp_ledger;
--rollback DROP INDEX uq_cultivation_rank_catalog_policy;

--changeset novelweb:cultivation-005-daily-xp-projection
CREATE TABLE cultivation_daily_xp_aggregates (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_date DATE NOT NULL,
    xp_earned BIGINT NOT NULL CHECK (xp_earned > 0),
    ledger_entry_count BIGINT NOT NULL CHECK (ledger_entry_count > 0),
    rebuilt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, activity_date)
);
--rollback DROP TABLE cultivation_daily_xp_aggregates;

--changeset novelweb:cultivation-006-daily-xp-projection-rebuild splitStatements:false
CREATE FUNCTION rebuild_cultivation_daily_xp_aggregate(
    target_user_id UUID,
    target_activity_date DATE
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM id FROM users WHERE id = target_user_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cannot rebuild cultivation XP for an unknown user'
            USING ERRCODE = '23503';
    END IF;

    DELETE FROM cultivation_daily_xp_aggregates
    WHERE user_id = target_user_id AND activity_date = target_activity_date;

    INSERT INTO cultivation_daily_xp_aggregates
        (user_id, activity_date, xp_earned, ledger_entry_count, rebuilt_at)
    SELECT user_id,
           activity_date,
           SUM(xp_delta)::BIGINT,
           COUNT(*)::BIGINT,
           clock_timestamp()
    FROM cultivation_xp_ledger
    WHERE user_id = target_user_id AND activity_date = target_activity_date
    GROUP BY user_id, activity_date;
END;
$$;
--rollback DROP FUNCTION rebuild_cultivation_daily_xp_aggregate(UUID, DATE);

--changeset novelweb:cultivation-007-xp-ledger-guard splitStatements:false
CREATE FUNCTION guard_cultivation_xp_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    profile_enabled BOOLEAN;
    profile_catalog_version VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Cultivation XP ledger entries are append-only'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        -- Account erasure may cascade-delete its private ledger; direct edits stay forbidden.
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Cultivation XP ledger entries are append-only'
            USING ERRCODE = '55000';
    END IF;

    PERFORM id
    FROM users
    WHERE id = NEW.user_id AND status = 'active'
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cultivation XP can only be recorded for an active user'
            USING ERRCODE = '55000';
    END IF;

    SELECT enabled, catalog_version
    INTO profile_enabled, profile_catalog_version
    FROM cultivation_profiles
    WHERE user_id = NEW.user_id
    FOR UPDATE;
    IF NOT FOUND OR NOT profile_enabled THEN
        RAISE EXCEPTION 'Cultivation XP requires an enabled profile'
            USING ERRCODE = '55000';
    END IF;
    IF profile_catalog_version IS DISTINCT FROM NEW.catalog_version THEN
        RAISE EXCEPTION 'Cultivation XP must use the profile pinned catalog version'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;
--rollback DROP FUNCTION guard_cultivation_xp_ledger_mutation();

--changeset novelweb:cultivation-008-xp-ledger-guard-trigger
CREATE TRIGGER trg_cultivation_xp_ledger_guard
    BEFORE INSERT OR UPDATE OR DELETE ON cultivation_xp_ledger
    FOR EACH ROW EXECUTE FUNCTION guard_cultivation_xp_ledger_mutation();
--rollback DROP TRIGGER trg_cultivation_xp_ledger_guard ON cultivation_xp_ledger;
