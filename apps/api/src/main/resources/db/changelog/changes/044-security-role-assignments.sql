--liquibase formatted sql

--changeset novelweb:security-044-role-assignments splitStatements:false rollbackSplitStatements:false
CREATE TABLE security_role_assignments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_code VARCHAR(40) NOT NULL
        CHECK (role_code IN ('system_admin', 'publication_reviewer')),
    assigned_by_user_id UUID,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reason_code VARCHAR(40) NOT NULL
        CHECK (reason_code IN (
            'REVIEW_CAPACITY', 'TEMPORARY_COVERAGE', 'ROLE_CORRECTION', 'INITIAL_ADMIN_BOOTSTRAP'
        )),
    revoked_by_user_id UUID,
    revoked_at TIMESTAMPTZ,
    revocation_reason_code VARCHAR(40)
        CHECK (revocation_reason_code IN ('ACCESS_ENDED', 'ROLE_CHANGE', 'SECURITY_REVIEW')),
    CHECK ((revoked_at IS NULL AND revoked_by_user_id IS NULL AND revocation_reason_code IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL AND revocation_reason_code IS NOT NULL)),
    CHECK (role_code <> 'system_admin' OR assigned_by_user_id IS NULL OR assigned_by_user_id <> user_id),
    CHECK (role_code <> 'publication_reviewer' OR assigned_by_user_id IS NOT NULL),
    CHECK (role_code <> 'publication_reviewer' OR assigned_by_user_id <> user_id),
    CHECK (role_code <> 'system_admin' OR reason_code = 'INITIAL_ADMIN_BOOTSTRAP' OR assigned_by_user_id IS NOT NULL)
);

CREATE UNIQUE INDEX uq_security_role_assignments_active
    ON security_role_assignments (user_id, role_code)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_security_role_assignments_active_role
    ON security_role_assignments (role_code, assigned_at DESC, id DESC)
    WHERE revoked_at IS NULL;

CREATE TABLE security_role_assignment_events (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL,
    target_user_id UUID NOT NULL,
    role_code VARCHAR(40) NOT NULL
        CHECK (role_code IN ('system_admin', 'publication_reviewer')),
    action_code VARCHAR(24) NOT NULL
        CHECK (action_code IN ('GRANTED', 'REVOKED', 'BOOTSTRAPPED')),
    actor_user_id UUID,
    operator_identity VARCHAR(160),
    ticket_reference VARCHAR(128),
    reason_code VARCHAR(40) NOT NULL
        CHECK (reason_code IN (
            'REVIEW_CAPACITY', 'TEMPORARY_COVERAGE', 'ROLE_CORRECTION',
            'ACCESS_ENDED', 'ROLE_CHANGE', 'SECURITY_REVIEW', 'INITIAL_ADMIN_BOOTSTRAP'
        )),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (
        (action_code = 'BOOTSTRAPPED' AND role_code = 'system_admin'
            AND actor_user_id IS NULL AND operator_identity IS NOT NULL
            AND ticket_reference IS NOT NULL
            AND reason_code = 'INITIAL_ADMIN_BOOTSTRAP')
        OR
        (action_code <> 'BOOTSTRAPPED' AND actor_user_id IS NOT NULL
            AND operator_identity IS NULL AND ticket_reference IS NULL
            AND reason_code <> 'INITIAL_ADMIN_BOOTSTRAP')
    )
);
CREATE INDEX ix_security_role_assignment_events_target
    ON security_role_assignment_events (target_user_id, occurred_at DESC, id DESC);
CREATE UNIQUE INDEX uq_security_role_assignment_single_bootstrap
    ON security_role_assignment_events (action_code)
    WHERE action_code = 'BOOTSTRAPPED';

CREATE TABLE security_role_assignment_idempotency (
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    assignment_id UUID,
    result_code VARCHAR(24) NOT NULL
        CHECK (result_code IN ('GRANTED', 'ALREADY_ACTIVE', 'REVOKED', 'ALREADY_REVOKED')),
    response_status SMALLINT NOT NULL CHECK (response_status IN (200, 201, 204)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_user_id, idempotency_key_sha256)
);

CREATE FUNCTION reject_security_role_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Security role audit records are append-only'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_security_role_assignment_event_immutable
    BEFORE UPDATE OR DELETE ON security_role_assignment_events
    FOR EACH ROW EXECUTE FUNCTION reject_security_role_audit_mutation();
CREATE TRIGGER trg_security_role_assignment_event_truncate_immutable
    BEFORE TRUNCATE ON security_role_assignment_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_security_role_audit_mutation();
CREATE TRIGGER trg_security_role_assignment_idempotency_immutable
    BEFORE UPDATE OR DELETE ON security_role_assignment_idempotency
    FOR EACH ROW EXECUTE FUNCTION reject_security_role_audit_mutation();
CREATE TRIGGER trg_security_role_assignment_idempotency_truncate_immutable
    BEFORE TRUNCATE ON security_role_assignment_idempotency
    FOR EACH STATEMENT EXECUTE FUNCTION reject_security_role_audit_mutation();

--rollback DROP TRIGGER trg_security_role_assignment_idempotency_immutable ON security_role_assignment_idempotency;
--rollback DROP TRIGGER trg_security_role_assignment_idempotency_truncate_immutable ON security_role_assignment_idempotency;
--rollback DROP TRIGGER trg_security_role_assignment_event_immutable ON security_role_assignment_events;
--rollback DROP TRIGGER trg_security_role_assignment_event_truncate_immutable ON security_role_assignment_events;
--rollback DROP FUNCTION reject_security_role_audit_mutation();
--rollback DROP TABLE security_role_assignment_idempotency;
--rollback DROP INDEX uq_security_role_assignment_single_bootstrap;
--rollback DROP TABLE security_role_assignment_events;
--rollback DROP TABLE security_role_assignments;
