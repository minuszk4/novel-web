--liquibase formatted sql

--changeset novelweb:publication-054-release-events splitStatements:false rollbackSplitStatements:false
CREATE TABLE publication_release_events (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    request_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    policy_sha256 BYTEA NOT NULL CHECK (octet_length(policy_sha256) = 32),
    snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(snapshot_sha256) = 32),
    publication_content_version BIGINT NOT NULL CHECK (publication_content_version > 0),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    released_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_publication_release_request
        FOREIGN KEY (request_id, work_id, actor_user_id)
        REFERENCES publication_requests(id, work_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_release_owner_work
        FOREIGN KEY (actor_user_id, work_id)
        REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_release_policy
        FOREIGN KEY (policy_version, policy_sha256)
        REFERENCES publication_rights_policy_documents(version, policy_sha256) ON DELETE RESTRICT,
    CONSTRAINT uq_publication_release_request UNIQUE (request_id),
    CONSTRAINT uq_publication_release_idempotency
        UNIQUE (actor_user_id, work_id, idempotency_key_sha256),
    CHECK (released_at <= clock_timestamp())
);

CREATE INDEX ix_publication_release_work_history
    ON publication_release_events (work_id, released_at DESC, id DESC);

CREATE FUNCTION validate_publication_release_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    owner_is_active BOOLEAN;
    work_visibility VARCHAR(16);
    work_content_version BIGINT;
    current_policy_version VARCHAR(64);
    current_policy_sha256 BYTEA;
    request_state VARCHAR(32);
    request_policy_version VARCHAR(64);
    request_policy_sha256 BYTEA;
    request_snapshot_sha256 BYTEA;
BEGIN
    SELECT owner.status = 'active', work.visibility, work.publication_content_version
      INTO owner_is_active, work_visibility, work_content_version
      FROM works work
      JOIN users owner ON owner.id = work.owner_user_id
      JOIN libraries library
        ON library.id = work.library_id
       AND library.owner_user_id = work.owner_user_id
       AND library.deleted_at IS NULL
       AND library.workspace_id IS NULL
      JOIN authoring_manuscript_creations creation
        ON creation.work_id = work.id
       AND creation.actor_user_id = work.owner_user_id
     WHERE work.id = NEW.work_id
       AND work.owner_user_id = NEW.actor_user_id
       AND work.source_kind = 'authored'
       AND work.status = 'active'
       AND work.deleted_at IS NULL
     FOR UPDATE OF work, owner, library, creation;
    IF NOT FOUND OR owner_is_active IS DISTINCT FROM TRUE
       OR work_visibility IS DISTINCT FROM 'private' THEN
        RAISE EXCEPTION 'An active owner-authored private manuscript is required for release'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.publication_content_version IS DISTINCT FROM work_content_version THEN
        RAISE EXCEPTION 'The manuscript changed before publication release'
            USING ERRCODE = '23514';
    END IF;

    SELECT state.current_state, request.policy_version, request.policy_sha256,
           request.snapshot_sha256
      INTO request_state, request_policy_version, request_policy_sha256,
           request_snapshot_sha256
      FROM publication_requests request
      JOIN publication_request_current_state state ON state.request_id = request.id
     WHERE request.id = NEW.request_id
       AND request.work_id = NEW.work_id
       AND request.owner_user_id = NEW.actor_user_id
     FOR UPDATE OF request;
    IF NOT FOUND OR request_state IS DISTINCT FROM 'APPROVED_UNPUBLISHED' THEN
        RAISE EXCEPTION 'A current approved-unpublished request is required for release'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM publication_requests newer
         WHERE newer.work_id = NEW.work_id
           AND (newer.request_version > (
                    SELECT request_version FROM publication_requests WHERE id = NEW.request_id
                )
                OR (newer.request_version = (
                    SELECT request_version FROM publication_requests WHERE id = NEW.request_id
                ) AND newer.id > NEW.request_id))
    ) THEN
        RAISE EXCEPTION 'Only the latest publication request can be released'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.policy_version IS DISTINCT FROM request_policy_version
       OR NEW.policy_sha256 IS DISTINCT FROM request_policy_sha256
       OR NEW.snapshot_sha256 IS DISTINCT FROM request_snapshot_sha256 THEN
        RAISE EXCEPTION 'The release receipt must match the approved request snapshot'
            USING ERRCODE = '23514';
    END IF;

    LOCK TABLE publication_rights_policy_documents IN SHARE MODE;

    SELECT version, policy_sha256
      INTO current_policy_version, current_policy_sha256
      FROM publication_rights_policy_documents
     WHERE status = 'APPROVED'
       AND effective_at <= clock_timestamp()
     ORDER BY effective_at DESC, version DESC
     LIMIT 1;
    IF NOT FOUND
       OR current_policy_version IS DISTINCT FROM request_policy_version
       OR current_policy_sha256 IS DISTINCT FROM request_policy_sha256 THEN
        RAISE EXCEPTION 'The approved rights policy changed before release'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM publication_rights_policy_acceptances acceptance
         WHERE acceptance.user_id = NEW.actor_user_id
           AND acceptance.work_id = NEW.work_id
           AND acceptance.policy_version = request_policy_version
           AND acceptance.policy_sha256 = request_policy_sha256
    ) THEN
        RAISE EXCEPTION 'The owner has not accepted the current rights policy'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM publication_request_scope_claims claim
         WHERE claim.request_id = NEW.request_id
           AND claim.rights_basis_code = 'NEEDS_REVIEW'
    ) THEN
        RAISE EXCEPTION 'A request with unresolved rights declarations cannot be released'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id AND scope.scope_code = 'PUBLIC_METADATA'
    ) OR NOT EXISTS (
        SELECT 1 FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id AND scope.scope_code = 'CHAPTER_TEXT'
    ) THEN
        RAISE EXCEPTION 'Public metadata and chapter text approval are required for release'
            USING ERRCODE = '23514';
    END IF;

    PERFORM approval.id
      FROM publication_scope_access_approvals approval
     WHERE approval.work_id = NEW.work_id
     ORDER BY approval.id
     FOR SHARE OF approval;

    IF EXISTS (
        SELECT 1
          FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id
           AND scope.scope_snapshot IS DISTINCT FROM
               publication_work_scope_snapshot(NEW.work_id, scope.scope_code)
    ) THEN
        RAISE EXCEPTION 'The approved content snapshot is stale'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id
           AND NOT EXISTS (
               SELECT 1
                 FROM publication_scope_access_approvals approval
                WHERE approval.work_id = NEW.work_id
                  AND approval.scope_code = scope.scope_code
                  AND approval.approved_snapshot = scope.scope_snapshot
                  AND approval.policy_version = request_policy_version
                  AND approval.policy_sha256 = request_policy_sha256
                  AND approval.approved_at <= clock_timestamp()
                  AND (approval.expires_at IS NULL OR approval.expires_at > clock_timestamp())
                  AND NOT EXISTS (
                      SELECT 1
                        FROM publication_scope_access_revocations revocation
                       WHERE revocation.approval_id = approval.id
                  )
           )
    ) THEN
        RAISE EXCEPTION 'Every requested publication scope must have a current reviewer approval'
            USING ERRCODE = '23514';
    END IF;

    NEW.released_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_publication_release_validate
    BEFORE INSERT ON publication_release_events
    FOR EACH ROW EXECUTE FUNCTION validate_publication_release_event();

CREATE FUNCTION publish_work_after_release_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    scope_code VARCHAR(32);
BEGIN
    UPDATE works
       SET visibility = 'public'
     WHERE id = NEW.work_id AND owner_user_id = NEW.actor_user_id
       AND visibility = 'private' AND status = 'active' AND deleted_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'The manuscript could not be released'
            USING ERRCODE = '23514';
    END IF;

    FOR scope_code IN
        SELECT scope.scope_code
          FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id
    LOOP
        IF NOT publication_scope_access_is_current(NEW.work_id, scope_code) THEN
            RAISE EXCEPTION 'The approved publication scope is not current'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_publication_release_publish_work
    AFTER INSERT ON publication_release_events
    FOR EACH ROW EXECUTE FUNCTION publish_work_after_release_event();

CREATE TRIGGER trg_publication_release_immutable
    BEFORE UPDATE OR DELETE ON publication_release_events
    FOR EACH ROW EXECUTE FUNCTION guard_publication_scope_access_audit_mutation();
CREATE TRIGGER trg_publication_release_truncate_immutable
    BEFORE TRUNCATE ON publication_release_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_scope_access_audit_truncate();

--rollback DROP TRIGGER trg_publication_release_truncate_immutable ON publication_release_events;
--rollback DROP TRIGGER trg_publication_release_immutable ON publication_release_events;
--rollback DROP TRIGGER trg_publication_release_publish_work ON publication_release_events;
--rollback DROP FUNCTION publish_work_after_release_event();
--rollback DROP TRIGGER trg_publication_release_validate ON publication_release_events;
--rollback DROP FUNCTION validate_publication_release_event();
--rollback DROP INDEX ix_publication_release_work_history;
--rollback DROP TABLE publication_release_events;
