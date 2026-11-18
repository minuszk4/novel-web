--liquibase formatted sql

--changeset novelweb:workspace-077-membership-audit
CREATE TABLE workspace_membership_audit_events (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    subject_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action_code VARCHAR(32) NOT NULL CHECK (action_code IN (
        'WORKSPACE_CREATED', 'MEMBER_GRANTED', 'MEMBER_ROLE_CHANGED', 'MEMBER_REVOKED',
        'MEMBER_REACTIVATED', 'OWNERSHIP_TRANSFERRED', 'INVITATION_ACCEPTED', 'MEMBERSHIP_BASELINE'
    )),
    reason_code VARCHAR(32) NOT NULL CHECK (reason_code IN (
        'INITIAL_OWNER', 'MANAGER_REQUEST', 'OWNER_HANDOVER', 'INVITATION_ACCEPTED',
        'PRE_AUDIT_BASELINE'
    )),
    previous_role VARCHAR(16) CHECK (previous_role IS NULL OR previous_role IN ('owner', 'admin', 'editor', 'reader')),
    new_role VARCHAR(16) CHECK (new_role IS NULL OR new_role IN ('owner', 'admin', 'editor', 'reader')),
    previous_status VARCHAR(16) CHECK (previous_status IS NULL OR previous_status IN ('active', 'revoked')),
    new_status VARCHAR(16) CHECK (new_status IS NULL OR new_status IN ('active', 'revoked')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((action_code = 'MEMBERSHIP_BASELINE') = (actor_user_id IS NULL))
);
CREATE INDEX ix_workspace_membership_audit_page
    ON workspace_membership_audit_events (workspace_id, event_id DESC);

INSERT INTO workspace_membership_audit_events (
    workspace_id, actor_user_id, subject_user_id, action_code, reason_code,
    previous_role, new_role, previous_status, new_status, occurred_at
)
SELECT id, created_by_user_id, created_by_user_id, 'WORKSPACE_CREATED', 'INITIAL_OWNER',
       NULL, 'owner', NULL, 'active', created_at
FROM workspaces;

INSERT INTO workspace_membership_audit_events (
    workspace_id, actor_user_id, subject_user_id, action_code, reason_code,
    previous_role, new_role, previous_status, new_status, occurred_at
)
SELECT workspace_id, NULL, user_id, 'MEMBERSHIP_BASELINE', 'PRE_AUDIT_BASELINE',
       NULL, role, NULL, status, updated_at
FROM workspace_members;
--rollback DROP INDEX ix_workspace_membership_audit_page;
--rollback DROP TABLE workspace_membership_audit_events;

--changeset novelweb:workspace-078-audit-immutability splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION reject_workspace_membership_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $function$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'workspace membership audit events are append-only'
        USING ERRCODE = '55000', CONSTRAINT = 'ck_workspace_membership_audit_append_only';
END
$function$;
--rollback DROP FUNCTION reject_workspace_membership_audit_mutation();

--changeset novelweb:workspace-079-audit-immutability-triggers
CREATE TRIGGER workspace_membership_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON workspace_membership_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_workspace_membership_audit_mutation();
CREATE TRIGGER workspace_membership_audit_no_truncate
    BEFORE TRUNCATE ON workspace_membership_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_workspace_membership_audit_mutation();
--rollback DROP TRIGGER workspace_membership_audit_no_truncate ON workspace_membership_audit_events;
--rollback DROP TRIGGER workspace_membership_audit_no_update_delete ON workspace_membership_audit_events;
