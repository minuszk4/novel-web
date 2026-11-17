--liquibase formatted sql

--changeset novelweb:publication-072-prospective-chapter-snapshot splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION publication_work_scope_snapshot_for_chapter(
    p_work_id UUID,
    p_scope_code VARCHAR,
    p_candidate_chapter_id UUID
)
RETURNS JSONB
LANGUAGE SQL
STABLE
AS $$
    SELECT CASE
        WHEN p_scope_code NOT IN (
            'PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE', 'TRANSLATION_ADAPTATION'
        ) THEN NULL
        WHEN p_scope_code = 'TRANSLATION_ADAPTATION' THEN jsonb_build_object(
            'scope', p_scope_code,
            'work', jsonb_build_object(
                'id', w.id,
                'ownerUserId', w.owner_user_id,
                'title', w.title,
                'author', w.author,
                'sourceLanguage', w.source_language,
                'sourceKind', w.source_kind,
                'authoringMetadataVersion', w.authoring_metadata_version
            ),
            'chapters', (
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'id', c.id,
                            'ordinal', c.ordinal,
                            'title', c.title,
                            'sourceLanguage', c.source_language,
                            'revisionId', c.source_revision_id,
                            'contentHash', encode(r.content_hash, 'hex')
                        ) ORDER BY c.ordinal, c.id
                    ),
                    '[]'::JSONB
                )
                FROM chapters c
                JOIN chapter_revisions r
                  ON r.id = c.source_revision_id AND r.chapter_id = c.id
                WHERE c.work_id = w.id
                  AND (c.status = 'ready' OR (c.id = p_candidate_chapter_id AND c.status = 'draft'))
                  AND c.deleted_at IS NULL
                  AND c.source_revision_id IS NOT NULL
            )
        )
        ELSE jsonb_build_object(
            'scope', p_scope_code,
            'work', jsonb_build_object(
                'id', w.id,
                'ownerUserId', w.owner_user_id,
                'libraryId', w.library_id,
                'title', w.title,
                'author', w.author,
                'sourceLanguage', w.source_language,
                'description', w.description,
                'metadataSchemaVersion', w.metadata_schema_version,
                'metadata', w.metadata,
                'authoringMetadataVersion', w.authoring_metadata_version
            ),
            'chapters', (
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'id', c.id,
                            'ordinal', c.ordinal,
                            'title', c.title,
                            'sourceLanguage', c.source_language,
                            'revisionId', CASE
                                WHEN p_scope_code = 'PUBLIC_METADATA' THEN NULL
                                ELSE c.source_revision_id
                            END,
                            'contentHash', CASE
                                WHEN p_scope_code = 'PUBLIC_METADATA' OR r.id IS NULL THEN NULL
                                ELSE encode(r.content_hash, 'hex')
                            END,
                            'segments', CASE
                                WHEN p_scope_code = 'PUBLIC_METADATA' THEN NULL
                                ELSE COALESCE((
                                    SELECT jsonb_agg(
                                        jsonb_build_object(
                                            'ordinal', s.ordinal,
                                            'type', s.segment_type,
                                            'anchor', s.stable_anchor,
                                            'checksum', encode(s.checksum, 'hex')
                                        ) ORDER BY s.ordinal
                                    )
                                    FROM segments s
                                    WHERE s.chapter_revision_id = r.id
                                ), '[]'::JSONB)
                            END
                        ) ORDER BY c.ordinal, c.id
                    ),
                    '[]'::JSONB
                )
                FROM chapters c
                LEFT JOIN chapter_revisions r
                  ON r.id = c.source_revision_id AND r.chapter_id = c.id
                WHERE c.work_id = w.id
                  AND (c.status = 'ready' OR (c.id = p_candidate_chapter_id AND c.status = 'draft'))
                  AND c.deleted_at IS NULL
                  AND c.source_revision_id IS NOT NULL
            )
        )
    END
    FROM works w
    WHERE w.id = p_work_id;
$$;

--rollback DROP FUNCTION publication_work_scope_snapshot_for_chapter(UUID, VARCHAR, UUID);

--changeset novelweb:publication-073-public-chapter-review-requests splitStatements:false rollbackSplitStatements:false
ALTER TABLE publication_requests
    ADD COLUMN target_chapter_id UUID,
    ADD COLUMN target_revision_id UUID,
    ADD CONSTRAINT ck_publication_request_target_pair
        CHECK ((target_chapter_id IS NULL) = (target_revision_id IS NULL)),
    ADD CONSTRAINT fk_publication_request_target_chapter
        FOREIGN KEY (work_id, target_chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_publication_request_target_revision
        FOREIGN KEY (target_chapter_id, target_revision_id)
        REFERENCES chapter_revisions(chapter_id, id) ON DELETE RESTRICT;

CREATE INDEX ix_publication_requests_target_chapter
    ON publication_requests (work_id, target_chapter_id, request_version DESC, id DESC)
    WHERE target_chapter_id IS NOT NULL;

CREATE OR REPLACE FUNCTION validate_publication_request_submission()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    owner_is_active BOOLEAN;
    current_policy_version VARCHAR(64);
    current_policy_sha256 BYTEA;
    acceptance_time TIMESTAMPTZ;
BEGIN
    LOCK TABLE publication_rights_policy_documents IN SHARE MODE;

    IF NEW.target_chapter_id IS NULL THEN
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
    ELSE
        SELECT u.status = 'active'
          INTO owner_is_active
          FROM users u
          JOIN works w ON w.owner_user_id = u.id
          JOIN libraries l ON l.id = w.library_id AND l.owner_user_id = w.owner_user_id
          JOIN authoring_manuscript_creations creation
            ON creation.work_id = w.id AND creation.actor_user_id = w.owner_user_id
          JOIN chapters chapter
            ON chapter.work_id = w.id
           AND chapter.id = NEW.target_chapter_id
           AND chapter.status = 'draft'
           AND chapter.deleted_at IS NULL
           AND chapter.source_revision_id = NEW.target_revision_id
          JOIN chapter_revisions revision
            ON revision.chapter_id = chapter.id
           AND revision.id = chapter.source_revision_id
           AND revision.created_by_user_id = w.owner_user_id
         WHERE u.id = NEW.owner_user_id
           AND w.id = NEW.work_id
           AND w.source_kind = 'authored'
           AND w.visibility = 'public'
           AND w.status = 'active'
           AND w.deleted_at IS NULL
           AND l.deleted_at IS NULL
           AND l.workspace_id IS NULL
         FOR UPDATE OF u, w, l, creation, chapter, revision;
    END IF;

    IF NOT FOUND OR owner_is_active IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'An active eligible owner-authored work is required'
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

CREATE OR REPLACE FUNCTION validate_publication_request_scope_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_work_id UUID;
    target_chapter_id UUID;
    current_snapshot JSONB;
BEGIN
    SELECT work_id, publication_requests.target_chapter_id
      INTO target_work_id, target_chapter_id
      FROM publication_requests
     WHERE id = NEW.request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A publication request is required for scope snapshots'
            USING ERRCODE = '23503';
    END IF;

    IF target_chapter_id IS NULL THEN
        SELECT publication_work_scope_snapshot(target_work_id, NEW.scope_code)
          INTO current_snapshot;
    ELSE
        SELECT publication_work_scope_snapshot_for_chapter(
            target_work_id, NEW.scope_code, target_chapter_id)
          INTO current_snapshot;
    END IF;
    IF current_snapshot IS NULL OR NEW.scope_snapshot IS DISTINCT FROM current_snapshot THEN
        RAISE EXCEPTION 'The submitted publication scope snapshot is stale or unsupported'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT')
       AND jsonb_array_length(COALESCE(current_snapshot -> 'chapters', '[]'::JSONB)) = 0 THEN
        RAISE EXCEPTION 'A publication request requires at least one chapter'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

--rollback CREATE OR REPLACE FUNCTION validate_publication_request_scope_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE target_work_id UUID; current_snapshot JSONB; BEGIN SELECT work_id INTO target_work_id FROM publication_requests WHERE id = NEW.request_id; IF NOT FOUND THEN RAISE EXCEPTION 'A publication request is required for scope snapshots' USING ERRCODE = '23503'; END IF; SELECT publication_work_scope_snapshot(target_work_id, NEW.scope_code) INTO current_snapshot; IF current_snapshot IS NULL OR NEW.scope_snapshot IS DISTINCT FROM current_snapshot THEN RAISE EXCEPTION 'The submitted publication scope snapshot is stale or unsupported' USING ERRCODE = '23514'; END IF; IF NEW.scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT') AND jsonb_array_length(COALESCE(current_snapshot -> 'chapters', '[]'::JSONB)) = 0 THEN RAISE EXCEPTION 'A publication request requires at least one ready chapter' USING ERRCODE = '23514'; END IF; RETURN NEW; END; $$;
--rollback CREATE OR REPLACE FUNCTION validate_publication_request_submission() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE owner_is_active BOOLEAN; current_policy_version VARCHAR(64); current_policy_sha256 BYTEA; acceptance_time TIMESTAMPTZ; BEGIN LOCK TABLE publication_rights_policy_documents IN SHARE MODE; SELECT u.status = 'active' INTO owner_is_active FROM users u JOIN works w ON w.owner_user_id = u.id JOIN libraries l ON l.id = w.library_id AND l.owner_user_id = w.owner_user_id JOIN authoring_manuscript_creations creation ON creation.work_id = w.id AND creation.actor_user_id = w.owner_user_id WHERE u.id = NEW.owner_user_id AND w.id = NEW.work_id AND w.source_kind = 'authored' AND w.visibility = 'private' AND w.status = 'active' AND w.deleted_at IS NULL AND l.deleted_at IS NULL AND l.workspace_id IS NULL FOR UPDATE OF u, w, l, creation; IF NOT FOUND OR owner_is_active IS DISTINCT FROM TRUE THEN RAISE EXCEPTION 'An active owner-authored private manuscript is required' USING ERRCODE = '23514'; END IF; SELECT version, policy_sha256 INTO current_policy_version, current_policy_sha256 FROM publication_rights_policy_documents WHERE status = 'APPROVED' AND effective_at <= clock_timestamp() ORDER BY effective_at DESC, version DESC LIMIT 1; IF NOT FOUND OR NEW.policy_version IS DISTINCT FROM current_policy_version OR NEW.policy_sha256 IS DISTINCT FROM current_policy_sha256 THEN RAISE EXCEPTION 'The current approved publication-rights policy is required' USING ERRCODE = '23514'; END IF; SELECT accepted_at INTO acceptance_time FROM publication_rights_policy_acceptances WHERE user_id = NEW.owner_user_id AND work_id = NEW.work_id AND policy_version = NEW.policy_version AND policy_sha256 = NEW.policy_sha256; IF NOT FOUND OR acceptance_time > clock_timestamp() THEN RAISE EXCEPTION 'The work owner must accept the current policy before submission' USING ERRCODE = '23514'; END IF; NEW.submitted_at := clock_timestamp(); RETURN NEW; END; $$;
--rollback ALTER TABLE publication_requests DROP CONSTRAINT fk_publication_request_target_revision;
--rollback ALTER TABLE publication_requests DROP CONSTRAINT fk_publication_request_target_chapter;
--rollback ALTER TABLE publication_requests DROP CONSTRAINT ck_publication_request_target_pair;
--rollback DROP INDEX ix_publication_requests_target_chapter;
--rollback ALTER TABLE publication_requests DROP COLUMN target_revision_id, DROP COLUMN target_chapter_id;

--changeset novelweb:publication-074-chapter-release-events splitStatements:false rollbackSplitStatements:false
CREATE TABLE chapter_publication_release_events (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    request_id UUID NOT NULL REFERENCES publication_requests(id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL,
    reviewer_user_id UUID NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    policy_sha256 BYTEA NOT NULL CHECK (octet_length(policy_sha256) = 32),
    snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(snapshot_sha256) = 32),
    previous_content_version BIGINT NOT NULL CHECK (previous_content_version > 0),
    publication_content_version BIGINT NOT NULL CHECK (publication_content_version > previous_content_version),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    released_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_chapter_release_owner_work
        FOREIGN KEY (actor_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_chapter_release_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_chapter_release_request UNIQUE (request_id),
    CONSTRAINT uq_chapter_release_idempotency UNIQUE (actor_user_id, work_id, idempotency_key_sha256),
    CHECK (reviewer_user_id <> actor_user_id),
    CHECK (released_at <= clock_timestamp())
);

CREATE INDEX ix_chapter_publication_release_history
    ON chapter_publication_release_events (work_id, released_at DESC, id DESC);

CREATE TABLE chapter_published_outbox_events (
    id UUID PRIMARY KEY,
    release_event_id UUID NOT NULL UNIQUE
        REFERENCES chapter_publication_release_events(id) ON DELETE RESTRICT,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'leased', 'completed', 'retry')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_chapter_outbox_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_chapter_outbox_owner_work
        FOREIGN KEY (owner_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CHECK ((status = 'leased') = (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX ix_chapter_published_outbox_claim
    ON chapter_published_outbox_events (next_attempt_at, created_at, id)
    WHERE status IN ('queued', 'retry');

CREATE FUNCTION validate_chapter_publication_release_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_visibility VARCHAR(16);
    current_content_version BIGINT;
    request_owner UUID;
    request_target_chapter UUID;
    request_target_revision UUID;
    request_policy_version VARCHAR(64);
    request_policy_sha256 BYTEA;
    request_snapshot_sha256 BYTEA;
    request_state VARCHAR(32);
    current_policy_version VARCHAR(64);
    current_policy_sha256 BYTEA;
    reviewer_id UUID;
BEGIN
    SELECT work.visibility, work.publication_content_version
      INTO work_visibility, current_content_version
      FROM works work
      JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
      JOIN libraries library
        ON library.id = work.library_id
       AND library.owner_user_id = work.owner_user_id
       AND library.deleted_at IS NULL
       AND library.workspace_id IS NULL
      JOIN authoring_manuscript_creations creation
        ON creation.work_id = work.id AND creation.actor_user_id = work.owner_user_id
      JOIN chapters chapter
        ON chapter.id = NEW.chapter_id
       AND chapter.work_id = work.id
       AND chapter.status = 'draft'
       AND chapter.deleted_at IS NULL
      JOIN chapter_revisions revision
        ON revision.id = chapter.source_revision_id
       AND revision.chapter_id = chapter.id
       AND revision.created_by_user_id = work.owner_user_id
     WHERE work.id = NEW.work_id
       AND work.owner_user_id = NEW.actor_user_id
       AND work.source_kind = 'authored'
       AND work.status = 'active'
       AND work.deleted_at IS NULL
       AND chapter.source_revision_id = (
           SELECT request.target_revision_id FROM publication_requests request WHERE request.id = NEW.request_id
       )
     FOR UPDATE OF work, owner, library, creation, chapter, revision;
    IF NOT FOUND OR work_visibility IS DISTINCT FROM 'public' THEN
        RAISE EXCEPTION 'An active public owner-authored work with a current draft chapter is required'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.previous_content_version IS DISTINCT FROM current_content_version THEN
        RAISE EXCEPTION 'The public work changed before chapter release'
            USING ERRCODE = '23514';
    END IF;

    SELECT request.owner_user_id, request.target_chapter_id, request.target_revision_id,
           request.policy_version, request.policy_sha256, request.snapshot_sha256,
           state.current_state
      INTO request_owner, request_target_chapter, request_target_revision,
           request_policy_version, request_policy_sha256, request_snapshot_sha256,
           request_state
      FROM publication_requests request
      JOIN publication_request_current_state state ON state.request_id = request.id
     WHERE request.id = NEW.request_id
       AND request.work_id = NEW.work_id
     FOR UPDATE OF request;
    IF NOT FOUND OR request_owner IS DISTINCT FROM NEW.actor_user_id
       OR request_target_chapter IS DISTINCT FROM NEW.chapter_id
       OR request_state IS DISTINCT FROM 'APPROVED_UNPUBLISHED' THEN
        RAISE EXCEPTION 'An approved request for this staged chapter is required'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM publication_requests newer
         WHERE newer.work_id = NEW.work_id
           AND newer.request_version > (SELECT request_version FROM publication_requests WHERE id = NEW.request_id)
    ) THEN
        RAISE EXCEPTION 'Only the latest publication request may release a staged chapter'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.snapshot_sha256 IS DISTINCT FROM request_snapshot_sha256 THEN
        RAISE EXCEPTION 'The release must use the reviewed snapshot'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.policy_version IS DISTINCT FROM request_policy_version
       OR NEW.policy_sha256 IS DISTINCT FROM request_policy_sha256 THEN
        RAISE EXCEPTION 'The release must use the reviewed rights policy'
            USING ERRCODE = '23514';
    END IF;

    SELECT event.actor_user_id
      INTO reviewer_id
      FROM publication_review_events event
     WHERE event.request_id = NEW.request_id
       AND event.action_code = 'APPROVED'
     ORDER BY event.event_sequence DESC
     LIMIT 1;
    IF NOT FOUND OR reviewer_id IS DISTINCT FROM NEW.reviewer_user_id
       OR reviewer_id = NEW.actor_user_id
       OR NOT EXISTS (
            SELECT 1
              FROM users reviewer
              JOIN security_role_assignments assignment ON assignment.user_id = reviewer.id
             WHERE reviewer.id = reviewer_id
               AND reviewer.status = 'active'
               AND assignment.role_code = 'publication_reviewer'
               AND assignment.assigned_at <= clock_timestamp()
               AND assignment.revoked_at IS NULL
       ) THEN
        RAISE EXCEPTION 'An active assigned reviewer who approved this request is required'
            USING ERRCODE = '23514';
    END IF;

    LOCK TABLE publication_rights_policy_documents IN SHARE MODE;
    SELECT version, policy_sha256
      INTO current_policy_version, current_policy_sha256
      FROM publication_rights_policy_documents
     WHERE status = 'APPROVED' AND effective_at <= clock_timestamp()
     ORDER BY effective_at DESC, version DESC LIMIT 1;
    IF NOT FOUND
       OR current_policy_version IS DISTINCT FROM request_policy_version
       OR current_policy_sha256 IS DISTINCT FROM request_policy_sha256
       OR NOT EXISTS (
            SELECT 1 FROM publication_rights_policy_acceptances acceptance
             WHERE acceptance.user_id = NEW.actor_user_id
               AND acceptance.work_id = NEW.work_id
               AND acceptance.policy_version = request_policy_version
               AND acceptance.policy_sha256 = request_policy_sha256
       ) THEN
        RAISE EXCEPTION 'The current accepted publication-rights policy is required'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM publication_request_scope_claims claim
         WHERE claim.request_id = NEW.request_id AND claim.rights_basis_code = 'NEEDS_REVIEW'
    ) THEN
        RAISE EXCEPTION 'Unresolved rights claims cannot be released'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id AND scope.scope_code = 'PUBLIC_METADATA'
    ) OR NOT EXISTS (
        SELECT 1 FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id AND scope.scope_code = 'CHAPTER_TEXT'
    ) THEN
        RAISE EXCEPTION 'Metadata and chapter-text scopes are required for release'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM publication_scope_access_approvals prior
         WHERE prior.work_id = NEW.work_id
           AND publication_scope_access_is_current(NEW.work_id, prior.scope_code)
           AND NOT EXISTS (
               SELECT 1 FROM publication_request_scope_snapshots requested
                WHERE requested.request_id = NEW.request_id
                  AND requested.scope_code = prior.scope_code
           )
    ) THEN
        RAISE EXCEPTION 'Every currently granted publication scope must be refreshed'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM publication_request_scope_snapshots scope
         WHERE scope.request_id = NEW.request_id
           AND scope.scope_snapshot IS DISTINCT FROM publication_work_scope_snapshot_for_chapter(
                NEW.work_id, scope.scope_code, NEW.chapter_id)
    ) THEN
        RAISE EXCEPTION 'The reviewed staged-chapter snapshot is stale'
            USING ERRCODE = '23514';
    END IF;

    NEW.released_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION publish_chapter_after_release_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    scope RECORD;
    new_content_version BIGINT;
BEGIN
    UPDATE chapters
       SET status = 'ready', updated_at = clock_timestamp()
     WHERE id = NEW.chapter_id AND work_id = NEW.work_id
       AND status = 'draft' AND source_revision_id = (
           SELECT request.target_revision_id FROM publication_requests request WHERE request.id = NEW.request_id
       );
    IF NOT FOUND THEN
        RAISE EXCEPTION 'The staged chapter changed before release'
            USING ERRCODE = '23514';
    END IF;

    SELECT publication_content_version INTO new_content_version
      FROM works WHERE id = NEW.work_id;
    IF new_content_version IS DISTINCT FROM NEW.publication_content_version THEN
        RAISE EXCEPTION 'The release content version does not match the chapter transition'
            USING ERRCODE = '23514';
    END IF;

    FOR scope IN
        SELECT scope_code, scope_snapshot
          FROM publication_request_scope_snapshots
         WHERE request_id = NEW.request_id
         ORDER BY scope_code
    LOOP
        IF scope.scope_snapshot IS DISTINCT FROM publication_work_scope_snapshot(NEW.work_id, scope.scope_code) THEN
            RAISE EXCEPTION 'The released scope snapshot does not match the reviewed chapter'
                USING ERRCODE = '23514';
        END IF;
        INSERT INTO publication_scope_access_approvals (
            id, work_id, scope_code, approved_snapshot, policy_version, policy_sha256,
            approved_by_user_id, reason_code
        ) VALUES (
            gen_random_uuid(), NEW.work_id, scope.scope_code, scope.scope_snapshot,
            NEW.policy_version, NEW.policy_sha256, NEW.reviewer_user_id, 'RIGHTS_REVIEWED'
        );
    END LOOP;

    INSERT INTO chapter_published_outbox_events (
        id, release_event_id, work_id, chapter_id, owner_user_id
    ) VALUES (
        gen_random_uuid(), NEW.id, NEW.work_id, NEW.chapter_id, NEW.actor_user_id
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_chapter_publication_release_validate
    BEFORE INSERT ON chapter_publication_release_events
    FOR EACH ROW EXECUTE FUNCTION validate_chapter_publication_release_event();
CREATE TRIGGER trg_chapter_publication_release_publish
    AFTER INSERT ON chapter_publication_release_events
    FOR EACH ROW EXECUTE FUNCTION publish_chapter_after_release_event();
CREATE TRIGGER trg_chapter_publication_release_immutable
    BEFORE UPDATE OR DELETE ON chapter_publication_release_events
    FOR EACH ROW EXECUTE FUNCTION guard_publication_review_audit_mutation();
CREATE TRIGGER trg_chapter_publication_release_truncate_immutable
    BEFORE TRUNCATE ON chapter_publication_release_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_review_audit_truncate();

--rollback DROP TRIGGER trg_chapter_publication_release_truncate_immutable ON chapter_publication_release_events;
--rollback DROP TRIGGER trg_chapter_publication_release_immutable ON chapter_publication_release_events;
--rollback DROP TRIGGER trg_chapter_publication_release_publish ON chapter_publication_release_events;
--rollback DROP TRIGGER trg_chapter_publication_release_validate ON chapter_publication_release_events;
--rollback DROP FUNCTION publish_chapter_after_release_event();
--rollback DROP FUNCTION validate_chapter_publication_release_event();
--rollback DROP INDEX ix_chapter_published_outbox_claim;
--rollback DROP TABLE chapter_published_outbox_events;
--rollback DROP INDEX ix_chapter_publication_release_history;
--rollback DROP TABLE chapter_publication_release_events;
