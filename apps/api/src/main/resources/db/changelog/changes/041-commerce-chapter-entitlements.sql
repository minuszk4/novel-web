--liquibase formatted sql

--changeset novelweb:commerce-008-chapter-entitlements
CREATE TABLE content_entitlements (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chapter_id UUID NOT NULL REFERENCES chapters(id) ON DELETE RESTRICT,
    price_version INTEGER NOT NULL CHECK (price_version > 0),
    credits_spent BIGINT NOT NULL CHECK (credits_spent > 0),
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_content_entitlement_actor_chapter UNIQUE (user_id, chapter_id),
    CONSTRAINT uq_content_entitlement_actor_idempotency UNIQUE (user_id, idempotency_key)
);
CREATE INDEX ix_content_entitlements_actor_recent
    ON content_entitlements (user_id, granted_at DESC, id DESC);
CREATE INDEX ix_content_entitlements_chapter_actor
    ON content_entitlements (chapter_id, user_id);
--rollback DROP TABLE content_entitlements;

--changeset novelweb:commerce-009-content-entitlements-append-only splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION guard_content_entitlement_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Content entitlements are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- Account erasure may cascade-delete private entitlement records.
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Content entitlements are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_content_entitlements_append_only
    BEFORE UPDATE OR DELETE ON content_entitlements
    FOR EACH ROW EXECUTE FUNCTION guard_content_entitlement_mutation();
--rollback DROP TRIGGER trg_content_entitlements_append_only ON content_entitlements;
--rollback DROP FUNCTION guard_content_entitlement_mutation();
