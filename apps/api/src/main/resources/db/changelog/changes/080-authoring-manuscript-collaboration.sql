--liquibase formatted sql

--changeset novelweb:authoring-080-manuscript-collaboration splitStatements:false rollbackSplitStatements:false
CREATE TABLE authoring_manuscript_invitations (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    invited_email TEXT NOT NULL CHECK (
        invited_email = lower(btrim(invited_email))
        AND length(invited_email) BETWEEN 3 AND 320
        AND invited_email LIKE '%@%'
    ),
    requested_permissions TEXT[] NOT NULL CHECK (
        cardinality(requested_permissions) BETWEEN 1 AND 2
        AND requested_permissions @> ARRAY['VIEW']::TEXT[]
        AND requested_permissions <@ ARRAY['VIEW', 'EDIT']::TEXT[]
    ),
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
    CHECK ((status = 'revoked') = (revoked_at IS NOT NULL)),
    CONSTRAINT fk_authoring_invitation_owner_work
        FOREIGN KEY (owner_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_authoring_invitation_authored_work
        FOREIGN KEY (work_id) REFERENCES authoring_manuscript_creations(work_id) ON DELETE RESTRICT,
    CONSTRAINT uq_authoring_manuscript_invitation_scope UNIQUE (id, work_id, owner_user_id)
);

CREATE UNIQUE INDEX uq_authoring_manuscript_invitation_pending_email
    ON authoring_manuscript_invitations (work_id, invited_email) WHERE status = 'pending';
CREATE INDEX ix_authoring_manuscript_invitation_manager_list
    ON authoring_manuscript_invitations (work_id, created_at DESC, id DESC)
    WHERE status = 'pending';
CREATE INDEX ix_authoring_manuscript_invitation_expiry
    ON authoring_manuscript_invitations (expires_at, id) WHERE status = 'pending';
CREATE INDEX ix_authoring_manuscript_invitation_actor_rate
    ON authoring_manuscript_invitations (invited_by_user_id, created_at DESC);
CREATE INDEX ix_authoring_manuscript_invitation_email_rate
    ON authoring_manuscript_invitations (invited_email, created_at DESC);

CREATE FUNCTION guard_authoring_manuscript_invitation_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Manuscript invitations cannot be truncated'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() > 1 THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'Manuscript invitations are retained'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'pending' OR NEW.accepted_at IS NOT NULL
           OR NEW.accepted_by_user_id IS NOT NULL OR NEW.rejected_at IS NOT NULL
           OR NEW.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'A manuscript invitation must start pending'
                USING ERRCODE = '23514';
        END IF;
        PERFORM user_row.id
          FROM users user_row
          JOIN works work_row ON work_row.owner_user_id = user_row.id
          JOIN libraries library_row
            ON library_row.id = work_row.library_id
           AND library_row.owner_user_id = work_row.owner_user_id
          JOIN authoring_manuscript_creations creation
            ON creation.work_id = work_row.id
           AND creation.actor_user_id = work_row.owner_user_id
         WHERE user_row.id = NEW.owner_user_id AND user_row.status = 'active'
           AND work_row.id = NEW.work_id AND work_row.source_kind = 'authored'
           AND work_row.status = 'active' AND work_row.deleted_at IS NULL
           AND library_row.workspace_id IS NULL AND library_row.visibility = 'private'
           AND library_row.deleted_at IS NULL
         FOR UPDATE OF user_row, work_row, library_row, creation;
        IF NOT FOUND OR NEW.invited_by_user_id IS DISTINCT FROM NEW.owner_user_id
            OR NEW.sender_consent_at > clock_timestamp()
            OR NEW.invited_email <> lower(btrim(NEW.invited_email))
            OR NEW.requested_permissions IS DISTINCT FROM (
                SELECT ARRAY(
                    SELECT DISTINCT permission
                      FROM unnest(NEW.requested_permissions) permission
                     ORDER BY permission
                )
            ) THEN
            RAISE EXCEPTION 'A current owner-issued manuscript invitation is required'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.work_id IS DISTINCT FROM OLD.work_id
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.invited_email IS DISTINCT FROM OLD.invited_email
       OR NEW.requested_permissions IS DISTINCT FROM OLD.requested_permissions
       OR NEW.invited_by_user_id IS DISTINCT FROM OLD.invited_by_user_id
       OR NEW.sender_consent_at IS DISTINCT FROM OLD.sender_consent_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'Manuscript invitation identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status <> 'pending' OR NEW.status NOT IN ('accepted', 'rejected', 'revoked', 'expired') THEN
        RAISE EXCEPTION 'Terminal manuscript invitation states are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'accepted' THEN
        PERFORM id FROM users
         WHERE id = NEW.accepted_by_user_id AND status = 'active'
           AND email_verified_at IS NOT NULL
           AND lower(contact_email) = NEW.invited_email
         FOR UPDATE;
        IF NOT FOUND OR NEW.accepted_at IS NULL OR NEW.accepted_at > clock_timestamp() THEN
            RAISE EXCEPTION 'A verified matching recipient is required'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.accepted_by_user_id IS NOT NULL OR NEW.accepted_at IS NOT NULL THEN
        RAISE EXCEPTION 'Non-accepted invitations cannot record a recipient'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_authoring_manuscript_invitation_transition
    BEFORE INSERT OR UPDATE OR DELETE ON authoring_manuscript_invitations
    FOR EACH ROW EXECUTE FUNCTION guard_authoring_manuscript_invitation_transition();
CREATE TRIGGER trg_authoring_manuscript_invitation_no_truncate
    BEFORE TRUNCATE ON authoring_manuscript_invitations
    FOR EACH STATEMENT EXECUTE FUNCTION guard_authoring_manuscript_invitation_transition();

CREATE TABLE authoring_manuscript_invitation_outbox (
    id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL UNIQUE
        REFERENCES authoring_manuscript_invitations(id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'queued' CHECK (
        status IN ('queued', 'retry', 'leased', 'sent', 'failed', 'cancelled')
    ),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    last_safe_failure_code TEXT CHECK (
        last_safe_failure_code IS NULL OR last_safe_failure_code IN (
            'SMTP_UNAVAILABLE', 'SMTP_REJECTED', 'LEASE_EXPIRED',
            'INVITATION_NOT_PENDING', 'RETRY_EXHAUSTED'
        )
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    CHECK ((status = 'leased') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CHECK ((status = 'sent') = (sent_at IS NOT NULL))
);
CREATE INDEX ix_authoring_manuscript_invitation_outbox_due
    ON authoring_manuscript_invitation_outbox (next_attempt_at, created_at, id)
    WHERE status IN ('queued', 'retry', 'leased');

CREATE FUNCTION guard_authoring_manuscript_invitation_outbox_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Manuscript invitation delivery history cannot be truncated'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() > 1 THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'Manuscript invitation delivery history is retained'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.invitation_id IS DISTINCT FROM OLD.invitation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Manuscript invitation delivery identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status IN ('sent', 'failed', 'cancelled') THEN
        RAISE EXCEPTION 'Terminal manuscript delivery state is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_authoring_manuscript_invitation_outbox_state
    BEFORE UPDATE OR DELETE ON authoring_manuscript_invitation_outbox
    FOR EACH ROW EXECUTE FUNCTION guard_authoring_manuscript_invitation_outbox_state();
CREATE TRIGGER trg_authoring_manuscript_invitation_outbox_no_truncate
    BEFORE TRUNCATE ON authoring_manuscript_invitation_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION guard_authoring_manuscript_invitation_outbox_state();

CREATE TABLE authoring_manuscript_invitation_events (
    id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL,
    work_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action_code TEXT NOT NULL CHECK (
        action_code IN ('INVITE_CREATED', 'INVITE_REVOKED', 'INVITE_ACCEPTED', 'INVITE_REJECTED', 'INVITE_EXPIRED')
    ),
    permissions TEXT[] NOT NULL CHECK (
        cardinality(permissions) BETWEEN 1 AND 2
        AND permissions @> ARRAY['VIEW']::TEXT[]
        AND permissions <@ ARRAY['VIEW', 'EDIT']::TEXT[]
    ),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_authoring_invitation_event_scope
        FOREIGN KEY (invitation_id, work_id, owner_user_id)
        REFERENCES authoring_manuscript_invitations(id, work_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_authoring_invitation_event_owner_work
        FOREIGN KEY (owner_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_authoring_manuscript_invitation_events_history
    ON authoring_manuscript_invitation_events (work_id, occurred_at, id);
CREATE FUNCTION reject_authoring_manuscript_invitation_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Manuscript invitation events are append-only'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_authoring_manuscript_invitation_events_immutable
    BEFORE UPDATE OR DELETE ON authoring_manuscript_invitation_events
    FOR EACH ROW EXECUTE FUNCTION reject_authoring_manuscript_invitation_event_mutation();
CREATE TRIGGER trg_authoring_manuscript_invitation_events_no_truncate
    BEFORE TRUNCATE ON authoring_manuscript_invitation_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_authoring_manuscript_invitation_event_mutation();

CREATE TABLE authoring_manuscript_contributors (
    id UUID NOT NULL UNIQUE,
    work_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    contributor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    invitation_id UUID NOT NULL UNIQUE
        REFERENCES authoring_manuscript_invitations(id) ON DELETE RESTRICT,
    permissions TEXT[] NOT NULL CHECK (
        cardinality(permissions) BETWEEN 1 AND 2
        AND permissions @> ARRAY['VIEW']::TEXT[]
        AND permissions <@ ARRAY['VIEW', 'EDIT']::TEXT[]
    ),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT pk_authoring_manuscript_contributors PRIMARY KEY (work_id, contributor_user_id),
    CONSTRAINT fk_authoring_contributor_owner_work
        FOREIGN KEY (owner_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CHECK (owner_user_id <> contributor_user_id)
);
CREATE INDEX ix_authoring_manuscript_contributors_actor
    ON authoring_manuscript_contributors (contributor_user_id, granted_at DESC, work_id)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_authoring_manuscript_contributors_work
    ON authoring_manuscript_contributors (work_id, granted_at DESC, contributor_user_id)
    WHERE revoked_at IS NULL;

CREATE FUNCTION guard_authoring_manuscript_contributor_grant() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    invitation_permissions TEXT[];
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Manuscript contributor grants cannot be truncated'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF pg_trigger_depth() > 1 THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'Manuscript contributor grants must be revoked'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 1 OR NEW.granted_at > clock_timestamp() OR NEW.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'A new manuscript contributor grant must be active at version one'
                USING ERRCODE = '23514';
        END IF;
        SELECT requested_permissions INTO invitation_permissions
          FROM authoring_manuscript_invitations
         WHERE id = NEW.invitation_id AND work_id = NEW.work_id
           AND owner_user_id = NEW.owner_user_id AND status = 'accepted'
           AND accepted_by_user_id = NEW.contributor_user_id
         FOR UPDATE;
        IF NOT FOUND OR invitation_permissions IS DISTINCT FROM NEW.permissions THEN
            RAISE EXCEPTION 'An accepted matching manuscript invitation is required'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.work_id IS DISTINCT FROM OLD.work_id
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.contributor_user_id IS DISTINCT FROM OLD.contributor_user_id
       OR NEW.granted_at IS DISTINCT FROM OLD.granted_at
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Manuscript contributor grant identity/version is invalid'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
        IF NEW.permissions IS DISTINCT FROM OLD.permissions
           OR NEW.invitation_id IS DISTINCT FROM OLD.invitation_id
           OR NEW.revoked_at > clock_timestamp() THEN
            RAISE EXCEPTION 'Grant revocation cannot alter permissions or provenance'
                USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.revoked_at IS NULL AND NEW.revoked_at IS NULL THEN
        IF NEW.permissions IS NOT DISTINCT FROM OLD.permissions THEN
            RAISE EXCEPTION 'A permission update must change the permission set'
                USING ERRCODE = '55000';
        END IF;
        IF NEW.invitation_id IS NOT DISTINCT FROM OLD.invitation_id THEN
            RAISE EXCEPTION 'A permission update must use its newly accepted invitation'
                USING ERRCODE = '55000';
        END IF;
        SELECT requested_permissions INTO invitation_permissions
          FROM authoring_manuscript_invitations
         WHERE id = NEW.invitation_id AND work_id = NEW.work_id
           AND owner_user_id = NEW.owner_user_id AND status = 'accepted'
           AND accepted_by_user_id = NEW.contributor_user_id
         FOR UPDATE;
        IF NOT FOUND OR invitation_permissions IS DISTINCT FROM NEW.permissions THEN
            RAISE EXCEPTION 'Permission changes require a matching accepted invitation'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS NULL THEN
        SELECT requested_permissions INTO invitation_permissions
          FROM authoring_manuscript_invitations
         WHERE id = NEW.invitation_id AND work_id = NEW.work_id
           AND owner_user_id = NEW.owner_user_id AND status = 'accepted'
           AND accepted_by_user_id = NEW.contributor_user_id
         FOR UPDATE;
        IF NEW.invitation_id IS NOT DISTINCT FROM OLD.invitation_id
           OR NOT FOUND OR invitation_permissions IS DISTINCT FROM NEW.permissions THEN
            RAISE EXCEPTION 'Reactivation requires a new accepted invitation'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'A revoked manuscript grant must be reactivated before further changes'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_authoring_manuscript_contributor_grant_guard
    BEFORE INSERT OR UPDATE OR DELETE ON authoring_manuscript_contributors
    FOR EACH ROW EXECUTE FUNCTION guard_authoring_manuscript_contributor_grant();
CREATE TRIGGER trg_authoring_manuscript_contributor_grant_no_truncate
    BEFORE TRUNCATE ON authoring_manuscript_contributors
    FOR EACH STATEMENT EXECUTE FUNCTION guard_authoring_manuscript_contributor_grant();

CREATE TABLE authoring_manuscript_contributor_events (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    contributor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action_code TEXT NOT NULL CHECK (
        action_code IN ('GRANTED', 'PERMISSIONS_CHANGED', 'REVOKED', 'REACTIVATED')
    ),
    previous_permissions TEXT[] CHECK (
        previous_permissions IS NULL OR (
            cardinality(previous_permissions) BETWEEN 1 AND 2
            AND previous_permissions @> ARRAY['VIEW']::TEXT[]
            AND previous_permissions <@ ARRAY['VIEW', 'EDIT']::TEXT[]
        )
    ),
    current_permissions TEXT[] CHECK (
        current_permissions IS NULL OR (
            cardinality(current_permissions) BETWEEN 1 AND 2
            AND current_permissions @> ARRAY['VIEW']::TEXT[]
            AND current_permissions <@ ARRAY['VIEW', 'EDIT']::TEXT[]
        )
    ),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_authoring_contributor_event_owner_work
        FOREIGN KEY (owner_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_authoring_manuscript_contributor_events_history
    ON authoring_manuscript_contributor_events (work_id, occurred_at, id);

CREATE FUNCTION reject_authoring_manuscript_contributor_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Manuscript contributor events are append-only'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_authoring_manuscript_contributor_events_immutable
    BEFORE UPDATE OR DELETE ON authoring_manuscript_contributor_events
    FOR EACH ROW EXECUTE FUNCTION reject_authoring_manuscript_contributor_event_mutation();
CREATE TRIGGER trg_authoring_manuscript_contributor_events_no_truncate
    BEFORE TRUNCATE ON authoring_manuscript_contributor_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_authoring_manuscript_contributor_event_mutation();

--rollback DROP TRIGGER trg_authoring_manuscript_contributor_events_no_truncate ON authoring_manuscript_contributor_events;
--rollback DROP TRIGGER trg_authoring_manuscript_contributor_events_immutable ON authoring_manuscript_contributor_events;
--rollback DROP FUNCTION reject_authoring_manuscript_contributor_event_mutation();
--rollback DROP TABLE authoring_manuscript_contributor_events;
--rollback DROP TRIGGER trg_authoring_manuscript_contributor_grant_no_truncate ON authoring_manuscript_contributors;
--rollback DROP TRIGGER trg_authoring_manuscript_contributor_grant_guard ON authoring_manuscript_contributors;
--rollback DROP FUNCTION guard_authoring_manuscript_contributor_grant();
--rollback DROP TABLE authoring_manuscript_contributors;
--rollback DROP TRIGGER trg_authoring_manuscript_invitation_outbox_no_truncate ON authoring_manuscript_invitation_outbox;
--rollback DROP TRIGGER trg_authoring_manuscript_invitation_outbox_state ON authoring_manuscript_invitation_outbox;
--rollback DROP FUNCTION guard_authoring_manuscript_invitation_outbox_state();
--rollback DROP INDEX ix_authoring_manuscript_invitation_outbox_due;
--rollback DROP TABLE authoring_manuscript_invitation_outbox;
--rollback DROP TRIGGER trg_authoring_manuscript_invitation_no_truncate ON authoring_manuscript_invitations;
--rollback DROP TRIGGER trg_authoring_manuscript_invitation_transition ON authoring_manuscript_invitations;
--rollback DROP FUNCTION guard_authoring_manuscript_invitation_transition();
--rollback DROP TRIGGER trg_authoring_manuscript_invitation_events_no_truncate ON authoring_manuscript_invitation_events;
--rollback DROP TRIGGER trg_authoring_manuscript_invitation_events_immutable ON authoring_manuscript_invitation_events;
--rollback DROP FUNCTION reject_authoring_manuscript_invitation_event_mutation();
--rollback DROP TABLE authoring_manuscript_invitation_events;
--rollback DROP INDEX ix_authoring_manuscript_invitation_email_rate;
--rollback DROP INDEX ix_authoring_manuscript_invitation_actor_rate;
--rollback DROP INDEX ix_authoring_manuscript_invitation_expiry;
--rollback DROP INDEX ix_authoring_manuscript_invitation_manager_list;
--rollback DROP INDEX uq_authoring_manuscript_invitation_pending_email;
--rollback DROP TABLE authoring_manuscript_invitations;
