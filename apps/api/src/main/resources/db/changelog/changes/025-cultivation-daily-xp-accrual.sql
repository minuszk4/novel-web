--liquibase formatted sql

--changeset novelweb:cultivation-013-xp-accrual-fields
ALTER TABLE cultivation_reading_sessions
    ADD COLUMN total_active_microseconds BIGINT NOT NULL DEFAULT 0
        CHECK (total_active_microseconds >= 0),
    ADD COLUMN closed_awarded_xp SMALLINT NOT NULL DEFAULT 0
        CHECK (closed_awarded_xp >= 0);
UPDATE cultivation_reading_sessions
SET total_active_microseconds = total_active_seconds * 1000000
WHERE total_active_seconds > 0;

ALTER TABLE cultivation_reading_session_events
    ADD COLUMN credited_microseconds BIGINT NOT NULL DEFAULT 0
        CHECK (credited_microseconds BETWEEN 0 AND 60000000),
    ADD COLUMN awarded_xp SMALLINT NOT NULL DEFAULT 0
        CHECK (awarded_xp >= 0);
--rollback ALTER TABLE cultivation_reading_session_events DROP COLUMN awarded_xp, DROP COLUMN credited_microseconds;
--rollback ALTER TABLE cultivation_reading_sessions DROP COLUMN closed_awarded_xp, DROP COLUMN total_active_microseconds;

--changeset novelweb:cultivation-014-daily-active-time
CREATE TABLE cultivation_daily_active_time (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_date DATE NOT NULL,
    eligible_microseconds BIGINT NOT NULL DEFAULT 0
        CHECK (eligible_microseconds BETWEEN 0 AND 86400000000),
    catalog_version VARCHAR(32) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, activity_date),
    CONSTRAINT fk_cultivation_daily_active_policy
        FOREIGN KEY (catalog_version, policy_version)
        REFERENCES cultivation_rank_catalogs (version, policy_version) ON DELETE RESTRICT
);
CREATE INDEX ix_cultivation_daily_active_cleanup
    ON cultivation_daily_active_time (updated_at);
--rollback DROP TABLE cultivation_daily_active_time;

--changeset novelweb:cultivation-015-xp-ledger-session-policy-guard splitStatements:false rollbackSplitStatements:false
CREATE OR REPLACE FUNCTION guard_cultivation_xp_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    profile_enabled BOOLEAN;
    profile_catalog_version VARCHAR(32);
    profile_policy_version VARCHAR(32);
    xp_per_active_minute SMALLINT;
    daily_xp_cap SMALLINT;
    session_user_id UUID;
    session_state VARCHAR(16);
    session_catalog_version VARCHAR(32);
    session_policy_version VARCHAR(32);
    session_started_at TIMESTAMPTZ;
    existing_daily_xp BIGINT;
    target_activity_date DATE;
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

    SELECT p.enabled, p.catalog_version, c.policy_version,
           c.xp_per_active_minute, c.daily_xp_cap
    INTO profile_enabled, profile_catalog_version, profile_policy_version,
         xp_per_active_minute, daily_xp_cap
    FROM cultivation_profiles p
    JOIN cultivation_rank_catalogs c ON c.version = p.catalog_version
    WHERE p.user_id = NEW.user_id
    FOR UPDATE OF p;
    IF NOT FOUND OR NOT profile_enabled THEN
        RAISE EXCEPTION 'Cultivation XP requires an enabled profile'
            USING ERRCODE = '55000';
    END IF;
    IF profile_catalog_version IS DISTINCT FROM NEW.catalog_version
       OR profile_policy_version IS DISTINCT FROM NEW.policy_version THEN
        RAISE EXCEPTION 'Cultivation XP must use the profile pinned catalog and policy versions'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.xp_delta <> xp_per_active_minute THEN
        RAISE EXCEPTION 'Cultivation XP must match the pinned active-minute award'
            USING ERRCODE = '55000';
    END IF;

    SELECT user_id, state, catalog_version, policy_version, started_at
    INTO session_user_id, session_state, session_catalog_version, session_policy_version,
         session_started_at
    FROM cultivation_reading_sessions
    WHERE id = NEW.source_session_id
    FOR UPDATE;
    IF NOT FOUND
       OR session_user_id IS DISTINCT FROM NEW.user_id
       OR session_state IS DISTINCT FROM 'active'
       OR session_catalog_version IS DISTINCT FROM NEW.catalog_version
       OR session_policy_version IS DISTINCT FROM NEW.policy_version
       OR NEW.activity_at < session_started_at
       OR NEW.activity_at > clock_timestamp() THEN
        RAISE EXCEPTION 'Cultivation XP must originate from a current actor-owned Reader session'
            USING ERRCODE = '55000';
    END IF;

    target_activity_date := (NEW.activity_at AT TIME ZONE 'UTC')::date;
    SELECT COALESCE(SUM(xp_delta), 0)
    INTO existing_daily_xp
    FROM cultivation_xp_ledger
    WHERE user_id = NEW.user_id AND activity_date = target_activity_date;
    IF existing_daily_xp + NEW.xp_delta > daily_xp_cap THEN
        RAISE EXCEPTION 'Cultivation daily XP cap has been reached'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;
--rollback CREATE OR REPLACE FUNCTION guard_cultivation_xp_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE profile_enabled BOOLEAN; profile_catalog_version VARCHAR(32); BEGIN IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'Cultivation XP ledger entries are append-only' USING ERRCODE = '55000'; END IF; IF TG_OP = 'DELETE' THEN IF pg_trigger_depth() > 1 THEN RETURN OLD; END IF; RAISE EXCEPTION 'Cultivation XP ledger entries are append-only' USING ERRCODE = '55000'; END IF; PERFORM id FROM users WHERE id = NEW.user_id AND status = 'active' FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'Cultivation XP can only be recorded for an active user' USING ERRCODE = '55000'; END IF; SELECT enabled, catalog_version INTO profile_enabled, profile_catalog_version FROM cultivation_profiles WHERE user_id = NEW.user_id FOR UPDATE; IF NOT FOUND OR NOT profile_enabled THEN RAISE EXCEPTION 'Cultivation XP requires an enabled profile' USING ERRCODE = '55000'; END IF; IF profile_catalog_version IS DISTINCT FROM NEW.catalog_version THEN RAISE EXCEPTION 'Cultivation XP must use the profile pinned catalog version' USING ERRCODE = '55000'; END IF; RETURN NEW; END; $$;
