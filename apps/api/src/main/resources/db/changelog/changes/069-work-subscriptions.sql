--liquibase formatted sql

--changeset novelweb:notifications-069-work-subscriptions splitStatements:false rollbackSplitStatements:false
CREATE TABLE work_subscriptions (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    followed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    muted_at TIMESTAMPTZ,
    unfollowed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_work_subscription_actor_work UNIQUE (actor_user_id, work_id),
    CHECK (notifications_enabled = FALSE OR active = TRUE),
    CHECK (active = (unfollowed_at IS NULL)),
    CHECK ((notifications_enabled = FALSE AND muted_at IS NOT NULL)
        OR (notifications_enabled = TRUE AND muted_at IS NULL)),
    CHECK (followed_at <= updated_at AND created_at <= updated_at)
);

CREATE INDEX ix_work_subscriptions_active_notifications
    ON work_subscriptions (work_id, actor_user_id)
    WHERE active = TRUE AND notifications_enabled = TRUE;

CREATE FUNCTION guard_work_subscription_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
           OR NEW.work_id IS DISTINCT FROM OLD.work_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.followed_at < OLD.followed_at
           OR NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'Work subscription identity and version are immutable'
                USING ERRCODE = '55000';
        END IF;
        NEW.updated_at := clock_timestamp();
        IF NEW.active = FALSE THEN
            NEW.notifications_enabled := FALSE;
            NEW.muted_at := COALESCE(NEW.muted_at, NEW.updated_at);
            NEW.unfollowed_at := COALESCE(NEW.unfollowed_at, NEW.updated_at);
        ELSIF NEW.notifications_enabled THEN
            IF OLD.active = FALSE THEN
                NEW.followed_at := NEW.updated_at;
            END IF;
            NEW.muted_at := NULL;
            NEW.unfollowed_at := NULL;
        ELSE
            NEW.muted_at := COALESCE(NEW.muted_at, NEW.updated_at);
            NEW.unfollowed_at := NULL;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_work_subscription_transition
    BEFORE UPDATE ON work_subscriptions
    FOR EACH ROW EXECUTE FUNCTION guard_work_subscription_transition();

CREATE FUNCTION guard_work_subscription_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Work subscription history is retained; transition it to inactive instead'
        USING ERRCODE = '55000';
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_work_subscription_no_delete
    BEFORE DELETE ON work_subscriptions
    FOR EACH ROW EXECUTE FUNCTION guard_work_subscription_delete();

--rollback DROP TRIGGER trg_work_subscription_no_delete ON work_subscriptions;
--rollback DROP FUNCTION guard_work_subscription_delete();
--rollback DROP TRIGGER trg_work_subscription_transition ON work_subscriptions;
--rollback DROP FUNCTION guard_work_subscription_transition();
--rollback DROP INDEX ix_work_subscriptions_active_notifications;
--rollback DROP TABLE work_subscriptions;
