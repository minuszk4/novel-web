--liquibase formatted sql

--changeset novelweb:workspace-076-email-invitations splitStatements:false rollbackSplitStatements:false
CREATE TABLE workspace_invitations (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    invited_email TEXT NOT NULL CHECK (
        invited_email = lower(btrim(invited_email))
        AND length(invited_email) BETWEEN 3 AND 320
        AND invited_email LIKE '%@%'
    ),
    requested_role TEXT NOT NULL CHECK (requested_role IN ('admin', 'editor', 'reader')),
    invited_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    sender_consent_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (
        status IN ('pending', 'accepted', 'rejected', 'revoked', 'expired')
    ),
    accepted_by_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    accepted_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at),
    CHECK ((status = 'accepted') = (accepted_at IS NOT NULL)),
    CHECK ((status = 'accepted') = (accepted_by_user_id IS NOT NULL)),
    CHECK ((status = 'rejected') = (rejected_at IS NOT NULL)),
    CHECK ((status = 'revoked') = (revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_workspace_invitations_pending_email
    ON workspace_invitations (workspace_id, invited_email) WHERE status = 'pending';
CREATE INDEX ix_workspace_invitations_manager_list
    ON workspace_invitations (workspace_id, created_at DESC, id DESC)
    WHERE status = 'pending';
CREATE INDEX ix_workspace_invitations_expiry
    ON workspace_invitations (expires_at, id) WHERE status = 'pending';
CREATE INDEX ix_workspace_invitations_actor_rate
    ON workspace_invitations (invited_by_user_id, created_at DESC);
CREATE INDEX ix_workspace_invitations_email_rate
    ON workspace_invitations (invited_email, created_at DESC);

CREATE FUNCTION guard_workspace_invitation_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Workspace invitations cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() > 1 THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'Workspace invitations can only be removed with their workspace'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
       OR NEW.invited_email IS DISTINCT FROM OLD.invited_email
       OR NEW.requested_role IS DISTINCT FROM OLD.requested_role
       OR NEW.invited_by_user_id IS DISTINCT FROM OLD.invited_by_user_id
       OR NEW.sender_consent_at IS DISTINCT FROM OLD.sender_consent_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'Workspace invitation identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'pending' OR NEW.status NOT IN ('accepted', 'rejected', 'revoked', 'expired') THEN
        RAISE EXCEPTION 'Workspace invitation terminal states are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_workspace_invitation_transition
    BEFORE UPDATE OR DELETE ON workspace_invitations
    FOR EACH ROW EXECUTE FUNCTION guard_workspace_invitation_transition();
CREATE TRIGGER trg_workspace_invitation_no_truncate
    BEFORE TRUNCATE ON workspace_invitations
    FOR EACH STATEMENT EXECUTE FUNCTION guard_workspace_invitation_transition();

CREATE TABLE workspace_invitation_outbox (
    id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL UNIQUE REFERENCES workspace_invitations(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'queued' CHECK (
        status IN ('queued', 'retry', 'leased', 'sent', 'failed', 'cancelled')
    ),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    last_safe_failure_code TEXT CHECK (
        last_safe_failure_code IS NULL OR last_safe_failure_code IN (
            'SMTP_UNAVAILABLE', 'SMTP_REJECTED', 'LEASE_EXPIRED', 'INVITATION_NOT_PENDING',
            'RETRY_EXHAUSTED'
        )
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    CHECK ((status = 'leased') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CHECK ((status = 'sent') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_workspace_invitation_outbox_due
    ON workspace_invitation_outbox (next_attempt_at, created_at, id)
    WHERE status IN ('queued', 'retry', 'leased');

CREATE FUNCTION guard_workspace_invitation_outbox_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Workspace invitation delivery history cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() > 1 THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'Workspace invitation delivery can only be removed with its workspace'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.invitation_id IS DISTINCT FROM OLD.invitation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Workspace invitation delivery identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status IN ('sent', 'failed', 'cancelled') THEN
        RAISE EXCEPTION 'Terminal workspace invitation delivery state is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_workspace_invitation_outbox_state
    BEFORE UPDATE OR DELETE ON workspace_invitation_outbox
    FOR EACH ROW EXECUTE FUNCTION guard_workspace_invitation_outbox_state();
CREATE TRIGGER trg_workspace_invitation_outbox_no_truncate
    BEFORE TRUNCATE ON workspace_invitation_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION guard_workspace_invitation_outbox_state();

--rollback DROP TRIGGER trg_workspace_invitation_outbox_no_truncate ON workspace_invitation_outbox;
--rollback DROP TRIGGER trg_workspace_invitation_outbox_state ON workspace_invitation_outbox;
--rollback DROP FUNCTION guard_workspace_invitation_outbox_state();
--rollback DROP TABLE workspace_invitation_outbox;
--rollback DROP TRIGGER trg_workspace_invitation_no_truncate ON workspace_invitations;
--rollback DROP TRIGGER trg_workspace_invitation_transition ON workspace_invitations;
--rollback DROP FUNCTION guard_workspace_invitation_transition();
--rollback DROP TABLE workspace_invitations;
