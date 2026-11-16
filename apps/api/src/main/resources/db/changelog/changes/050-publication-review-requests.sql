--liquibase formatted sql

--changeset novelweb:publication-058-immutable-review-request-ledger splitStatements:false rollbackSplitStatements:false
CREATE TABLE publication_requests (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    request_version INTEGER NOT NULL CHECK (request_version > 0),
    supersedes_request_id UUID,
    policy_version VARCHAR(64) NOT NULL,
    policy_sha256 BYTEA NOT NULL CHECK (octet_length(policy_sha256) = 32),
    snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(snapshot_sha256) = 32),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_publication_request_work_version UNIQUE (work_id, request_version),
    CONSTRAINT uq_publication_request_owner_scope UNIQUE (id, work_id, owner_user_id),
    CONSTRAINT uq_publication_request_idempotency
        UNIQUE (owner_user_id, work_id, idempotency_key_sha256),
    CONSTRAINT fk_publication_request_owner_work
        FOREIGN KEY (owner_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_request_authored_work
        FOREIGN KEY (work_id) REFERENCES authoring_manuscript_creations(work_id) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_request_policy_acceptance
        FOREIGN KEY (owner_user_id, work_id, policy_version)
        REFERENCES publication_rights_policy_acceptances(user_id, work_id, policy_version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_publication_request_policy_snapshot
        FOREIGN KEY (policy_version, policy_sha256)
        REFERENCES publication_rights_policy_documents(version, policy_sha256) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_request_supersedes_same_owner_work
        FOREIGN KEY (supersedes_request_id, work_id, owner_user_id)
        REFERENCES publication_requests(id, work_id, owner_user_id) ON DELETE RESTRICT,
    CHECK (submitted_at <= clock_timestamp())
);

CREATE TABLE publication_request_scope_snapshots (
    request_id UUID NOT NULL REFERENCES publication_requests(id) ON DELETE RESTRICT,
    scope_code VARCHAR(32) NOT NULL
        CHECK (scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE')),
    scope_snapshot JSONB NOT NULL CHECK (jsonb_typeof(scope_snapshot) = 'object'),
    snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(snapshot_sha256) = 32),
    PRIMARY KEY (request_id, scope_code)
);

CREATE TABLE publication_request_scope_claims (
    request_id UUID NOT NULL,
    scope_code VARCHAR(32) NOT NULL,
    rights_basis_code VARCHAR(40) NOT NULL
        CHECK (rights_basis_code IN (
            'ORIGINAL_WORK', 'LICENSE_OR_PERMISSION', 'PUBLIC_DOMAIN', 'NEEDS_REVIEW'
        )),
    declared_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (request_id, scope_code, rights_basis_code),
    CONSTRAINT fk_publication_request_claim_scope
        FOREIGN KEY (request_id, scope_code)
        REFERENCES publication_request_scope_snapshots(request_id, scope_code) ON DELETE RESTRICT,
    CHECK (declared_at <= clock_timestamp())
);

CREATE TABLE publication_review_events (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES publication_requests(id) ON DELETE RESTRICT,
    event_sequence INTEGER NOT NULL CHECK (event_sequence > 0),
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    actor_role_code VARCHAR(32) NOT NULL CHECK (
        actor_role_code IN ('author', 'publication_reviewer')
    ),
    action_code VARCHAR(32) NOT NULL CHECK (
        action_code IN (
            'SUBMITTED', 'NEEDS_INFORMATION', 'APPROVED', 'REJECTED',
            'SUPERSEDED', 'WITHDRAWN'
        )
    ),
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL CHECK (
        to_state IN (
            'PENDING_REVIEW', 'NEEDS_INFORMATION', 'APPROVED_UNPUBLISHED',
            'REJECTED', 'SUPERSEDED', 'WITHDRAWN'
        )
    ),
    reason_code VARCHAR(40),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(snapshot_sha256) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_publication_review_event_sequence UNIQUE (request_id, event_sequence),
    CONSTRAINT uq_publication_review_event_idempotency
        UNIQUE (request_id, actor_user_id, idempotency_key_sha256),
    CHECK (created_at <= clock_timestamp()),
    CHECK (
        (action_code = 'SUBMITTED' AND from_state IS NULL AND to_state = 'PENDING_REVIEW'
            AND reason_code IS NULL)
        OR (action_code = 'NEEDS_INFORMATION' AND from_state = 'PENDING_REVIEW'
            AND to_state = 'NEEDS_INFORMATION'
            AND reason_code IS NOT NULL
            AND reason_code IN ('INCOMPLETE_CLAIM', 'MORE_INFORMATION', 'SCOPE_UNCLEAR'))
        OR (action_code = 'APPROVED' AND from_state = 'PENDING_REVIEW'
            AND to_state = 'APPROVED_UNPUBLISHED'
            AND reason_code IS NOT NULL
            AND reason_code IN ('RIGHTS_REVIEWED', 'SCOPE_CONFIRMED'))
        OR (action_code = 'REJECTED' AND from_state = 'PENDING_REVIEW'
            AND to_state = 'REJECTED'
            AND reason_code IS NOT NULL
            AND reason_code IN ('RIGHTS_NOT_VERIFIED', 'OUT_OF_SCOPE', 'POLICY_MISMATCH'))
        OR (action_code = 'SUPERSEDED' AND from_state IN (
                'NEEDS_INFORMATION', 'REJECTED', 'APPROVED_UNPUBLISHED'
            ) AND to_state = 'SUPERSEDED' AND reason_code IS NULL)
        OR (action_code = 'WITHDRAWN' AND from_state IN ('PENDING_REVIEW', 'NEEDS_INFORMATION')
            AND to_state = 'WITHDRAWN' AND reason_code IS NULL)
    )
);

CREATE INDEX ix_publication_requests_owner_history
    ON publication_requests (owner_user_id, submitted_at DESC, id DESC);
CREATE INDEX ix_publication_requests_work_version
    ON publication_requests (work_id, request_version DESC, id DESC);
CREATE INDEX ix_publication_review_events_latest
    ON publication_review_events (request_id, event_sequence DESC, id DESC);
CREATE INDEX ix_publication_review_events_queue
    ON publication_review_events (action_code, created_at DESC, request_id DESC);

CREATE VIEW publication_request_current_state AS
SELECT DISTINCT ON (event.request_id)
       event.request_id, event.id AS event_id, event.event_sequence,
       event.action_code, event.to_state AS current_state, event.actor_user_id,
       event.created_at AS changed_at
  FROM publication_review_events event
 ORDER BY event.request_id, event.event_sequence DESC, event.id DESC;

CREATE FUNCTION validate_publication_request_submission()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    owner_is_active BOOLEAN;
    current_policy_version VARCHAR(64);
    current_policy_sha256 BYTEA;
    acceptance_time TIMESTAMPTZ;
BEGIN
    -- Keep the policy selected at submission stable until the request transaction commits.
    LOCK TABLE publication_rights_policy_documents IN SHARE MODE;

    SELECT u.status = 'active'
      INTO owner_is_active
      FROM users u
      JOIN works w ON w.owner_user_id = u.id
      JOIN libraries l ON l.id = w.library_id AND l.owner_user_id = w.owner_user_id
      JOIN authoring_manuscript_creations creation
        ON creation.work_id = w.id AND creation.actor_user_id = w.owner_user_id
     WHERE u.id = NEW.owner_user_id
       AND w.id = NEW.work_id
       AND w.source_kind = 'authored'
       AND w.visibility = 'private'
       AND w.status = 'active'
       AND w.deleted_at IS NULL
       AND l.deleted_at IS NULL
       AND l.workspace_id IS NULL
     FOR UPDATE OF u, w, l, creation;
    IF NOT FOUND OR owner_is_active IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'An active owner-authored private manuscript is required'
            USING ERRCODE = '23514';
    END IF;

    SELECT version, policy_sha256
      INTO current_policy_version, current_policy_sha256
      FROM publication_rights_policy_documents
     WHERE status = 'APPROVED'
       AND effective_at <= clock_timestamp()
     ORDER BY effective_at DESC, version DESC
     LIMIT 1;
    IF NOT FOUND
       OR NEW.policy_version IS DISTINCT FROM current_policy_version
       OR NEW.policy_sha256 IS DISTINCT FROM current_policy_sha256 THEN
        RAISE EXCEPTION 'The current approved publication-rights policy is required'
            USING ERRCODE = '23514';
    END IF;

    SELECT accepted_at
      INTO acceptance_time
      FROM publication_rights_policy_acceptances
     WHERE user_id = NEW.owner_user_id
       AND work_id = NEW.work_id
       AND policy_version = NEW.policy_version
       AND policy_sha256 = NEW.policy_sha256;
    IF NOT FOUND OR acceptance_time > clock_timestamp() THEN
        RAISE EXCEPTION 'The work owner must accept the current policy before submission'
            USING ERRCODE = '23514';
    END IF;

    NEW.submitted_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_publication_request_submission_validate
    BEFORE INSERT ON publication_requests
    FOR EACH ROW EXECUTE FUNCTION validate_publication_request_submission();

CREATE FUNCTION validate_publication_request_scope_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_work_id UUID;
    current_snapshot JSONB;
BEGIN
    SELECT work_id INTO target_work_id
      FROM publication_requests
     WHERE id = NEW.request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A publication request is required for scope snapshots'
            USING ERRCODE = '23503';
    END IF;

    SELECT publication_work_scope_snapshot(target_work_id, NEW.scope_code)
      INTO current_snapshot;
    IF current_snapshot IS NULL OR NEW.scope_snapshot IS DISTINCT FROM current_snapshot THEN
        RAISE EXCEPTION 'The submitted publication scope snapshot is stale or unsupported'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT')
       AND jsonb_array_length(COALESCE(current_snapshot -> 'chapters', '[]'::JSONB)) = 0 THEN
        RAISE EXCEPTION 'A publication request requires at least one ready chapter'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_publication_request_scope_snapshot_validate
    BEFORE INSERT ON publication_request_scope_snapshots
    FOR EACH ROW EXECUTE FUNCTION validate_publication_request_scope_snapshot();

CREATE FUNCTION validate_publication_request_complete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM publication_request_scope_snapshots
         WHERE request_id = NEW.id AND scope_code = 'PUBLIC_METADATA'
    ) OR NOT EXISTS (
        SELECT 1 FROM publication_request_scope_snapshots
         WHERE request_id = NEW.id AND scope_code = 'CHAPTER_TEXT'
    ) THEN
        RAISE EXCEPTION 'A publication request must snapshot metadata and chapter text scopes'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM publication_request_scope_snapshots snapshot
         WHERE snapshot.request_id = NEW.id
           AND NOT EXISTS (
               SELECT 1 FROM publication_request_scope_claims claim
                WHERE claim.request_id = snapshot.request_id
                  AND claim.scope_code = snapshot.scope_code
           )
    ) THEN
        RAISE EXCEPTION 'Every submitted scope must include an author rights declaration'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM publication_review_events
         WHERE request_id = NEW.id
           AND event_sequence = 1
           AND action_code = 'SUBMITTED'
    ) THEN
        RAISE EXCEPTION 'A publication request and its submission event must commit together'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_publication_request_complete
    AFTER INSERT ON publication_requests
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_publication_request_complete();

CREATE FUNCTION validate_publication_review_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_owner UUID;
    request_digest BYTEA;
    current_state VARCHAR(32);
    latest_event_sequence INTEGER;
    reviewer_authorized BOOLEAN;
BEGIN
    SELECT owner_user_id, snapshot_sha256
      INTO request_owner, request_digest
      FROM publication_requests
     WHERE id = NEW.request_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A publication request is required for review events'
            USING ERRCODE = '23503';
    END IF;
    IF NEW.snapshot_sha256 IS DISTINCT FROM request_digest THEN
        RAISE EXCEPTION 'A review event must refer to the submitted snapshot'
            USING ERRCODE = '23514';
    END IF;
    NEW.created_at := clock_timestamp();

    SELECT event.to_state, event.event_sequence
      INTO current_state, latest_event_sequence
      FROM publication_review_events event
     WHERE event.request_id = NEW.request_id
     ORDER BY event.event_sequence DESC
     LIMIT 1;

    IF NOT FOUND THEN
        IF NEW.action_code <> 'SUBMITTED'
           OR NEW.event_sequence <> 1
           OR NEW.from_state IS NOT NULL
           OR NEW.actor_user_id <> request_owner THEN
            RAISE EXCEPTION 'The request owner must create the initial submission event'
                USING ERRCODE = '23514';
        END IF;
        NEW.actor_role_code := 'author';
        IF NOT EXISTS (
            SELECT 1 FROM users
             WHERE id = NEW.actor_user_id AND status = 'active'
        ) THEN
            RAISE EXCEPTION 'An active work owner is required to submit a publication request'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.idempotency_key_sha256 IS DISTINCT FROM (
            SELECT idempotency_key_sha256
              FROM publication_requests
             WHERE id = NEW.request_id
        ) THEN
            RAISE EXCEPTION 'The submission event must use the request idempotency key'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.event_sequence <> latest_event_sequence + 1
           OR NEW.from_state IS DISTINCT FROM current_state THEN
            RAISE EXCEPTION 'The publication request state changed; reload and retry'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.action_code IN ('NEEDS_INFORMATION', 'APPROVED', 'REJECTED') THEN
            IF NEW.actor_user_id = request_owner THEN
                RAISE EXCEPTION 'The request owner cannot review their own publication request'
                    USING ERRCODE = '23514';
            END IF;
            SELECT TRUE INTO reviewer_authorized
              FROM security_role_assignments assignment
              JOIN users reviewer
                ON reviewer.id = assignment.user_id AND reviewer.status = 'active'
             WHERE assignment.user_id = NEW.actor_user_id
               AND assignment.role_code = 'publication_reviewer'
               AND assignment.assigned_at <= clock_timestamp()
               AND assignment.revoked_at IS NULL
             ORDER BY assignment.assigned_at DESC, assignment.id DESC
             LIMIT 1
             FOR UPDATE OF assignment;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'An active publication reviewer is required'
                    USING ERRCODE = '23514';
            END IF;
            NEW.actor_role_code := 'publication_reviewer';
            IF NEW.action_code = 'APPROVED' AND EXISTS (
                SELECT 1 FROM publication_request_scope_claims claim
                 WHERE claim.request_id = NEW.request_id
                   AND claim.rights_basis_code = 'NEEDS_REVIEW'
            ) THEN
                RAISE EXCEPTION 'A request with unresolved rights declarations cannot be approved'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.action_code IN ('SUPERSEDED', 'WITHDRAWN') THEN
            IF NEW.actor_user_id <> request_owner THEN
                RAISE EXCEPTION 'Only the work owner can supersede or withdraw a request'
                    USING ERRCODE = '23514';
            END IF;
            NEW.actor_role_code := 'author';
        ELSE
            RAISE EXCEPTION 'The publication review event action is not valid in this state'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.action_code = 'SUBMITTED' AND NEW.request_sha256 IS DISTINCT FROM (
        SELECT request_sha256 FROM publication_requests WHERE id = NEW.request_id
    ) THEN
        RAISE EXCEPTION 'The submission event must match the request digest'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_publication_review_event_validate
    BEFORE INSERT ON publication_review_events
    FOR EACH ROW EXECUTE FUNCTION validate_publication_review_event();

CREATE FUNCTION guard_publication_review_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Publication request and review audit records are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION reject_publication_review_audit_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Publication request and review audit records cannot be truncated'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_publication_request_immutable
    BEFORE UPDATE OR DELETE ON publication_requests
    FOR EACH ROW EXECUTE FUNCTION guard_publication_review_audit_mutation();
CREATE TRIGGER trg_publication_request_truncate_immutable
    BEFORE TRUNCATE ON publication_requests
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_review_audit_truncate();
CREATE TRIGGER trg_publication_request_scope_snapshot_immutable
    BEFORE UPDATE OR DELETE ON publication_request_scope_snapshots
    FOR EACH ROW EXECUTE FUNCTION guard_publication_review_audit_mutation();
CREATE TRIGGER trg_publication_request_scope_snapshot_truncate_immutable
    BEFORE TRUNCATE ON publication_request_scope_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_review_audit_truncate();
CREATE TRIGGER trg_publication_request_scope_claim_immutable
    BEFORE UPDATE OR DELETE ON publication_request_scope_claims
    FOR EACH ROW EXECUTE FUNCTION guard_publication_review_audit_mutation();
CREATE TRIGGER trg_publication_request_scope_claim_truncate_immutable
    BEFORE TRUNCATE ON publication_request_scope_claims
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_review_audit_truncate();
CREATE TRIGGER trg_publication_review_event_immutable
    BEFORE UPDATE OR DELETE ON publication_review_events
    FOR EACH ROW EXECUTE FUNCTION guard_publication_review_audit_mutation();
CREATE TRIGGER trg_publication_review_event_truncate_immutable
    BEFORE TRUNCATE ON publication_review_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_review_audit_truncate();

--rollback DROP TRIGGER trg_publication_review_event_truncate_immutable ON publication_review_events;
--rollback DROP TRIGGER trg_publication_review_event_immutable ON publication_review_events;
--rollback DROP TRIGGER trg_publication_request_scope_claim_truncate_immutable ON publication_request_scope_claims;
--rollback DROP TRIGGER trg_publication_request_scope_claim_immutable ON publication_request_scope_claims;
--rollback DROP TRIGGER trg_publication_request_scope_snapshot_truncate_immutable ON publication_request_scope_snapshots;
--rollback DROP TRIGGER trg_publication_request_scope_snapshot_immutable ON publication_request_scope_snapshots;
--rollback DROP TRIGGER trg_publication_request_truncate_immutable ON publication_requests;
--rollback DROP TRIGGER trg_publication_request_immutable ON publication_requests;
--rollback DROP FUNCTION reject_publication_review_audit_truncate();
--rollback DROP FUNCTION guard_publication_review_audit_mutation();
--rollback DROP TRIGGER trg_publication_review_event_validate ON publication_review_events;
--rollback DROP FUNCTION validate_publication_review_event();
--rollback DROP TRIGGER trg_publication_request_complete ON publication_requests;
--rollback DROP FUNCTION validate_publication_request_complete();
--rollback DROP TRIGGER trg_publication_request_scope_snapshot_validate ON publication_request_scope_snapshots;
--rollback DROP FUNCTION validate_publication_request_scope_snapshot();
--rollback DROP TRIGGER trg_publication_request_submission_validate ON publication_requests;
--rollback DROP FUNCTION validate_publication_request_submission();
--rollback DROP VIEW publication_request_current_state;
--rollback DROP INDEX ix_publication_review_events_queue;
--rollback DROP INDEX ix_publication_review_events_latest;
--rollback DROP INDEX ix_publication_requests_work_version;
--rollback DROP INDEX ix_publication_requests_owner_history;
--rollback DROP TABLE publication_review_events;
--rollback DROP TABLE publication_request_scope_claims;
--rollback DROP TABLE publication_request_scope_snapshots;
--rollback DROP TABLE publication_requests;
