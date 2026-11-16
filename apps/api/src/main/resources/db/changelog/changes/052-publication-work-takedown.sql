--liquibase formatted sql

--changeset novelweb:publication-052-work-takedown-events splitStatements:false rollbackSplitStatements:false
CREATE TABLE publication_work_takedown_events (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    actor_role_code VARCHAR(32) NOT NULL
        CHECK (actor_role_code IN ('publication_reviewer', 'system_admin')),
    reason_code VARCHAR(40) NOT NULL
        CHECK (reason_code IN (
            'RIGHTS_WITHDRAWN', 'REVIEW_INVALIDATED', 'POLICY_CHANGED', 'EMERGENCY_TAKEDOWN'
        )),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    revoked_approval_count INTEGER NOT NULL CHECK (revoked_approval_count >= 0),
    previous_visibility VARCHAR(16) NOT NULL
        CHECK (previous_visibility IN ('private', 'unlisted', 'public')),
    current_visibility VARCHAR(16) NOT NULL DEFAULT 'private'
        CHECK (current_visibility = 'private'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_publication_work_takedown_idempotency
        UNIQUE (actor_user_id, idempotency_key_sha256),
    CHECK (created_at <= clock_timestamp())
);

CREATE INDEX ix_publication_work_takedown_history
    ON publication_work_takedown_events (work_id, created_at DESC, id DESC);

CREATE FUNCTION validate_publication_work_takedown_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_owner_id UUID;
BEGIN
    SELECT owner_user_id
      INTO work_owner_id
      FROM works
     WHERE id = NEW.work_id
       AND source_kind = 'authored'
       AND deleted_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A current authored work is required for publication takedown'
            USING ERRCODE = '23514';
    END IF;
    IF work_owner_id = NEW.actor_user_id THEN
        RAISE EXCEPTION 'A work owner cannot revoke publication approval'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM security_role_assignments assignment
          JOIN users actor ON actor.id = assignment.user_id AND actor.status = 'active'
         WHERE assignment.user_id = NEW.actor_user_id
           AND assignment.role_code = NEW.actor_role_code
           AND assignment.role_code IN ('publication_reviewer', 'system_admin')
           AND assignment.assigned_at <= NEW.created_at
           AND (assignment.revoked_at IS NULL OR assignment.revoked_at > NEW.created_at)
    ) THEN
        RAISE EXCEPTION 'An active reviewer or system administrator is required for publication takedown'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_publication_work_takedown_validate
    BEFORE INSERT ON publication_work_takedown_events
    FOR EACH ROW EXECUTE FUNCTION validate_publication_work_takedown_event();
CREATE TRIGGER trg_publication_work_takedown_immutable
    BEFORE UPDATE OR DELETE ON publication_work_takedown_events
    FOR EACH ROW EXECUTE FUNCTION guard_publication_scope_access_audit_mutation();
CREATE TRIGGER trg_publication_work_takedown_truncate_immutable
    BEFORE TRUNCATE ON publication_work_takedown_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_scope_access_audit_truncate();

--rollback DROP TRIGGER trg_publication_work_takedown_truncate_immutable ON publication_work_takedown_events;
--rollback DROP TRIGGER trg_publication_work_takedown_immutable ON publication_work_takedown_events;
--rollback DROP TRIGGER trg_publication_work_takedown_validate ON publication_work_takedown_events;
--rollback DROP FUNCTION validate_publication_work_takedown_event();
--rollback DROP INDEX ix_publication_work_takedown_history;
--rollback DROP TABLE publication_work_takedown_events;
