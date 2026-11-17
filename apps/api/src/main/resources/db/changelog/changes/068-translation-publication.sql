--liquibase formatted sql

--changeset novelweb:translation-068-version-manual-review-binding
ALTER TABLE translation_candidate_review_submissions
    ADD CONSTRAINT uq_translation_review_submission_snapshot_binding
        UNIQUE (id, candidate_id, snapshot_sha256);

ALTER TABLE translation_versions
    ADD COLUMN review_submission_id UUID,
    ADD COLUMN review_snapshot_sha256 BYTEA,
    ADD CONSTRAINT ck_translation_version_review_binding CHECK (
        (review_submission_id IS NULL AND review_snapshot_sha256 IS NULL)
        OR (review_submission_id IS NOT NULL AND octet_length(review_snapshot_sha256) = 32)
    ),
    ADD CONSTRAINT fk_translation_version_review_snapshot
        FOREIGN KEY (review_submission_id, candidate_id, review_snapshot_sha256)
        REFERENCES translation_candidate_review_submissions(id, candidate_id, snapshot_sha256)
        ON DELETE RESTRICT,
    ADD CONSTRAINT uq_translation_version_source_binding
        UNIQUE (id, source_revision_id);

ALTER TABLE translation_versions DROP CONSTRAINT uq_translation_version_candidate;
CREATE INDEX ix_translation_versions_candidate
    ON translation_versions (candidate_id, version_no DESC);

--rollback DROP INDEX ix_translation_versions_candidate;
--rollback ALTER TABLE translation_versions ADD CONSTRAINT uq_translation_version_candidate UNIQUE (candidate_id);
--rollback ALTER TABLE translation_versions DROP CONSTRAINT uq_translation_version_source_binding;
--rollback ALTER TABLE translation_versions DROP CONSTRAINT fk_translation_version_review_snapshot;
--rollback ALTER TABLE translation_versions DROP CONSTRAINT ck_translation_version_review_binding;
--rollback ALTER TABLE translation_versions DROP COLUMN review_snapshot_sha256;
--rollback ALTER TABLE translation_versions DROP COLUMN review_submission_id;
--rollback ALTER TABLE translation_candidate_review_submissions DROP CONSTRAINT uq_translation_review_submission_snapshot_binding;

--changeset novelweb:translation-069-immutable-version-segments
CREATE TABLE translation_version_segments (
    translation_version_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_segment_id UUID NOT NULL,
    segment_ordinal INTEGER NOT NULL CHECK (segment_ordinal > 0),
    segment_type VARCHAR(16) NOT NULL CHECK (segment_type IN ('paragraph', 'heading', 'quote')),
    stable_anchor VARCHAR(160) NOT NULL
        CHECK (stable_anchor ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'),
    translated_text TEXT NOT NULL
        CHECK (length(btrim(translated_text)) > 0 AND octet_length(translated_text) <= 1048576),
    translated_sha256 BYTEA NOT NULL CHECK (octet_length(translated_sha256) = 32),
    PRIMARY KEY (translation_version_id, source_segment_id),
    CONSTRAINT uq_translation_version_segment_ordinal
        UNIQUE (translation_version_id, segment_ordinal),
    CONSTRAINT fk_translation_version_segment_version
        FOREIGN KEY (translation_version_id, source_revision_id)
        REFERENCES translation_versions(id, source_revision_id) ON DELETE CASCADE,
    CONSTRAINT fk_translation_version_segment_source
        FOREIGN KEY (source_revision_id, source_segment_id)
        REFERENCES segments(chapter_revision_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_translation_version_segments_order
    ON translation_version_segments (translation_version_id, segment_ordinal);

--rollback DROP INDEX ix_translation_version_segments_order;
--rollback DROP TABLE translation_version_segments;

--changeset novelweb:translation-070-release-events
CREATE TABLE translation_release_events (
    id UUID PRIMARY KEY,
    translation_version_id UUID NOT NULL UNIQUE
        REFERENCES translation_versions(id) ON DELETE RESTRICT,
    review_submission_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    review_snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(review_snapshot_sha256) = 32),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE RESTRICT,
    chapter_id UUID NOT NULL REFERENCES chapters(id) ON DELETE RESTRICT,
    target_language VARCHAR(35) NOT NULL
        CHECK (target_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    source_revision_id UUID NOT NULL REFERENCES chapter_revisions(id) ON DELETE RESTRICT,
    action_code VARCHAR(16) NOT NULL CHECK (action_code IN ('PUBLISH', 'ROLLBACK')),
    rights_approval_id UUID NOT NULL
        REFERENCES publication_scope_access_approvals(id) ON DELETE RESTRICT,
    rights_snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(rights_snapshot_sha256) = 32),
    policy_version VARCHAR(64) NOT NULL,
    policy_sha256 BYTEA NOT NULL CHECK (octet_length(policy_sha256) = 32),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    released_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_translation_release_version_context
        FOREIGN KEY (translation_version_id, source_revision_id)
        REFERENCES translation_versions(id, source_revision_id) ON DELETE RESTRICT,
    CONSTRAINT fk_translation_release_review_snapshot
        FOREIGN KEY (review_submission_id, candidate_id, review_snapshot_sha256)
        REFERENCES translation_candidate_review_submissions(id, candidate_id, snapshot_sha256)
        ON DELETE RESTRICT,
    CONSTRAINT uq_translation_release_idempotency
        UNIQUE (owner_user_id, work_id, chapter_id, target_language, idempotency_key_sha256),
    CHECK (released_at <= clock_timestamp())
);
CREATE INDEX ix_translation_release_chapter_history
    ON translation_release_events (chapter_id, target_language, released_at DESC, id DESC);
CREATE UNIQUE INDEX uq_translation_release_published_submission
    ON translation_release_events (review_submission_id)
    WHERE action_code = 'PUBLISH';

--rollback DROP INDEX uq_translation_release_published_submission;
--rollback DROP INDEX ix_translation_release_chapter_history;
--rollback DROP TABLE translation_release_events;

--changeset novelweb:translation-071-publication-immutability splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION guard_translation_version_segment_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Published translation version segments are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Published translation version segments are immutable'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_translation_version_segments_immutable
    BEFORE UPDATE OR DELETE ON translation_version_segments
    FOR EACH ROW EXECUTE FUNCTION guard_translation_version_segment_immutable();
CREATE TRIGGER trg_translation_version_segments_no_truncate
    BEFORE TRUNCATE ON translation_version_segments
    FOR EACH STATEMENT EXECUTE FUNCTION guard_translation_version_segment_immutable();

CREATE FUNCTION guard_translation_release_event_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Translation release events are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Translation release events are immutable'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_translation_release_events_immutable
    BEFORE UPDATE OR DELETE ON translation_release_events
    FOR EACH ROW EXECUTE FUNCTION guard_translation_release_event_immutable();
CREATE TRIGGER trg_translation_release_events_no_truncate
    BEFORE TRUNCATE ON translation_release_events
    FOR EACH STATEMENT EXECUTE FUNCTION guard_translation_release_event_immutable();

CREATE FUNCTION validate_translation_release_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    version_row RECORD;
    submission_row RECORD;
    work_owner UUID;
    current_policy RECORD;
    approval_row RECORD;
    invalid_segment BOOLEAN;
    version_segment_count INTEGER;
BEGIN
    SELECT version.id, version.candidate_id, version.work_id, version.chapter_id,
           version.target_language, version.source_revision_id, version.version_no,
           version.status, version.rollback_of_version_id, version.created_by_user_id,
           version.review_submission_id, version.review_snapshot_sha256,
           chapter.source_revision_id AS current_source_revision_id,
           source_revision.content_hash AS current_source_sha256,
           work.owner_user_id, work.visibility AS work_visibility,
           work.source_kind, work.status AS work_status,
           chapter.status AS chapter_status, chapter.deleted_at AS chapter_deleted_at,
           owner.status AS owner_status
      INTO version_row
      FROM translation_versions version
      JOIN works work ON work.id = version.work_id
      JOIN chapters chapter ON chapter.id = version.chapter_id AND chapter.work_id = work.id
      JOIN chapter_revisions source_revision
        ON source_revision.id = chapter.source_revision_id
       AND source_revision.chapter_id = chapter.id
      JOIN users owner ON owner.id = work.owner_user_id
     WHERE version.id = NEW.translation_version_id
     FOR UPDATE OF version, work, chapter, owner;
    IF NOT FOUND OR version_row.owner_user_id IS DISTINCT FROM NEW.owner_user_id
       OR version_row.work_id IS DISTINCT FROM NEW.work_id
       OR version_row.chapter_id IS DISTINCT FROM NEW.chapter_id
       OR version_row.target_language IS DISTINCT FROM NEW.target_language
       OR version_row.source_revision_id IS DISTINCT FROM NEW.source_revision_id
       OR version_row.review_submission_id IS DISTINCT FROM NEW.review_submission_id
       OR version_row.review_snapshot_sha256 IS DISTINCT FROM NEW.review_snapshot_sha256
       OR version_row.created_by_user_id IS DISTINCT FROM NEW.owner_user_id
       OR version_row.status IS DISTINCT FROM 'current'
       OR version_row.work_status IS DISTINCT FROM 'active'
       OR version_row.work_visibility IS DISTINCT FROM 'public'
       OR version_row.source_kind IS DISTINCT FROM 'authored'
       OR version_row.owner_status IS DISTINCT FROM 'active'
       OR version_row.chapter_status IS DISTINCT FROM 'ready'
       OR version_row.chapter_deleted_at IS NOT NULL
       OR version_row.current_source_revision_id IS DISTINCT FROM NEW.source_revision_id THEN
        RAISE EXCEPTION 'Translation release is not bound to the current owner and source'
            USING ERRCODE = '23514';
    END IF;

    SELECT submission.candidate_id, submission.work_id, submission.chapter_id,
           submission.target_language, submission.source_revision_id, submission.source_sha256,
           submission.snapshot_sha256, submission.segment_count, submission.status,
           decision.action_code, decision.snapshot_sha256 AS decision_snapshot_sha256
      INTO submission_row
      FROM translation_candidate_review_submissions submission
      JOIN translation_candidate_review_decisions decision
        ON decision.submission_id = submission.id
       AND decision.action_code = 'ACCEPT'
       AND decision.snapshot_sha256 = submission.snapshot_sha256
     WHERE submission.id = NEW.review_submission_id
     FOR SHARE OF submission;
    IF NOT FOUND OR submission_row.status IS DISTINCT FROM 'accepted'
       OR submission_row.candidate_id IS DISTINCT FROM NEW.candidate_id
       OR submission_row.work_id IS DISTINCT FROM NEW.work_id
       OR submission_row.chapter_id IS DISTINCT FROM NEW.chapter_id
       OR submission_row.target_language IS DISTINCT FROM NEW.target_language
       OR submission_row.source_revision_id IS DISTINCT FROM NEW.source_revision_id
       OR submission_row.snapshot_sha256 IS DISTINCT FROM NEW.review_snapshot_sha256
       OR submission_row.source_sha256 IS DISTINCT FROM version_row.current_source_sha256 THEN
        RAISE EXCEPTION 'An accepted current manual translation snapshot is required'
            USING ERRCODE = '23514';
    END IF;

    IF NOT publication_scope_access_is_current(NEW.work_id, 'TRANSLATION_ADAPTATION') THEN
        RAISE EXCEPTION 'Current translation adaptation rights are required'
            USING ERRCODE = '23514';
    END IF;

    SELECT approval.id, approval.approved_snapshot, approval.policy_version,
           approval.policy_sha256
      INTO approval_row
      FROM publication_scope_access_approvals approval
      JOIN publication_rights_policy_documents policy
        ON policy.version = approval.policy_version
       AND policy.policy_sha256 = approval.policy_sha256
       AND policy.status = 'APPROVED'
       AND policy.effective_at <= NEW.released_at
     WHERE approval.id = NEW.rights_approval_id
       AND approval.work_id = NEW.work_id
       AND approval.scope_code = 'TRANSLATION_ADAPTATION'
       AND approval.approved_snapshot = publication_work_scope_snapshot(
           NEW.work_id, 'TRANSLATION_ADAPTATION')
       AND approval.approved_at <= NEW.released_at
       AND (approval.expires_at IS NULL OR approval.expires_at > NEW.released_at)
       AND NOT EXISTS (
           SELECT 1 FROM publication_scope_access_revocations revocation
            WHERE revocation.approval_id = approval.id
       );
    IF NOT FOUND THEN
        RAISE EXCEPTION 'The bound translation adaptation approval is not current'
            USING ERRCODE = '23514';
    END IF;

    SELECT version, policy_sha256
      INTO current_policy
      FROM publication_rights_policy_documents
     WHERE status = 'APPROVED' AND effective_at <= NEW.released_at
     ORDER BY effective_at DESC, version DESC
     LIMIT 1;
    IF NOT FOUND OR current_policy.version IS DISTINCT FROM approval_row.policy_version
       OR current_policy.policy_sha256 IS DISTINCT FROM approval_row.policy_sha256
       OR NEW.policy_version IS DISTINCT FROM approval_row.policy_version
       OR NEW.policy_sha256 IS DISTINCT FROM approval_row.policy_sha256
       THEN
        RAISE EXCEPTION 'The release must bind the current rights policy and scope snapshot'
            USING ERRCODE = '23514';
    END IF;

    IF version_row.rollback_of_version_id IS NULL THEN
        IF NEW.action_code IS DISTINCT FROM 'PUBLISH' THEN
            RAISE EXCEPTION 'A publication event must match a first release'
                USING ERRCODE = '23514';
        END IF;
        SELECT EXISTS (
            SELECT 1
              FROM translation_candidate_review_submission_segments snapshot_segment
              JOIN translation_candidate_segment_edits edit
                ON edit.candidate_id = snapshot_segment.candidate_id
               AND edit.source_segment_id = snapshot_segment.source_segment_id
               AND edit.edit_revision = snapshot_segment.edit_revision
              JOIN segments source_segment
                ON source_segment.id = snapshot_segment.source_segment_id
               AND source_segment.chapter_revision_id = NEW.source_revision_id
              LEFT JOIN translation_version_segments version_segment
                ON version_segment.translation_version_id = NEW.translation_version_id
               AND version_segment.source_segment_id = snapshot_segment.source_segment_id
             WHERE snapshot_segment.submission_id = NEW.review_submission_id
               AND (
                   version_segment.source_segment_id IS NULL
                   OR version_segment.source_revision_id IS DISTINCT FROM NEW.source_revision_id
                   OR version_segment.segment_ordinal IS DISTINCT FROM snapshot_segment.segment_ordinal
                   OR version_segment.segment_type IS DISTINCT FROM source_segment.segment_type
                   OR version_segment.stable_anchor IS DISTINCT FROM source_segment.stable_anchor
                   OR version_segment.translated_text IS DISTINCT FROM edit.translated_text
                   OR version_segment.translated_sha256 IS DISTINCT FROM edit.translated_sha256
               )
        ) INTO invalid_segment;
        SELECT count(*) INTO version_segment_count
          FROM translation_version_segments segment
         WHERE segment.translation_version_id = NEW.translation_version_id;
        IF invalid_segment OR version_segment_count <> submission_row.segment_count THEN
            RAISE EXCEPTION 'Published version segments must exactly match the accepted snapshot'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.action_code IS DISTINCT FROM 'ROLLBACK'
           OR NOT EXISTS (
               SELECT 1
                 FROM translation_versions target
                 JOIN translation_release_events target_release
                   ON target_release.translation_version_id = target.id
                WHERE target.id = version_row.rollback_of_version_id
                  AND target.chapter_id = NEW.chapter_id
                  AND target.target_language = NEW.target_language
                  AND target.source_revision_id = NEW.source_revision_id
           ) THEN
            RAISE EXCEPTION 'Rollback must target a previously released version of this current source'
                USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            SELECT 1
              FROM translation_version_segments target_segment
              LEFT JOIN translation_version_segments rollback_segment
                ON rollback_segment.translation_version_id = NEW.translation_version_id
               AND rollback_segment.source_segment_id = target_segment.source_segment_id
             WHERE target_segment.translation_version_id = version_row.rollback_of_version_id
               AND (
                   rollback_segment.source_segment_id IS NULL
                   OR rollback_segment.source_revision_id IS DISTINCT FROM target_segment.source_revision_id
                   OR rollback_segment.segment_ordinal IS DISTINCT FROM target_segment.segment_ordinal
                   OR rollback_segment.segment_type IS DISTINCT FROM target_segment.segment_type
                   OR rollback_segment.stable_anchor IS DISTINCT FROM target_segment.stable_anchor
                   OR rollback_segment.translated_text IS DISTINCT FROM target_segment.translated_text
                   OR rollback_segment.translated_sha256 IS DISTINCT FROM target_segment.translated_sha256
               )
        ) OR (
            SELECT count(*) FROM translation_version_segments rollback_segment
             WHERE rollback_segment.translation_version_id = NEW.translation_version_id
        ) <> (
            SELECT count(*) FROM translation_version_segments target_segment
             WHERE target_segment.translation_version_id = version_row.rollback_of_version_id
        ) THEN
            RAISE EXCEPTION 'Rollback version segments must be an exact copy of the retained target'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1 FROM translation_versions other_current
         WHERE other_current.chapter_id = NEW.chapter_id
           AND other_current.target_language = NEW.target_language
           AND other_current.status = 'current'
           AND other_current.id <> NEW.translation_version_id
    ) THEN
        RAISE EXCEPTION 'The prior current version must be superseded atomically'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_translation_release_event_validate
    BEFORE INSERT ON translation_release_events
    FOR EACH ROW EXECUTE FUNCTION validate_translation_release_event();

CREATE FUNCTION require_translation_release_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.review_submission_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM translation_release_events event
            WHERE event.translation_version_id = NEW.id
       ) THEN
        RAISE EXCEPTION 'Every manually reviewed translation version requires an owner release event'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_translation_version_release_required
    AFTER INSERT ON translation_versions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_translation_release_event();

CREATE FUNCTION guard_translation_version_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.review_submission_id IS NOT NULL
       AND OLD.status = 'current' AND NEW.status = 'superseded'
       AND ROW(
           NEW.id, NEW.work_id, NEW.chapter_id, NEW.target_language, NEW.source_revision_id,
           NEW.candidate_id, NEW.version_no, NEW.rollback_of_version_id, NEW.created_by_user_id,
           NEW.created_at, NEW.review_submission_id, NEW.review_snapshot_sha256
       ) IS NOT DISTINCT FROM ROW(
           OLD.id, OLD.work_id, OLD.chapter_id, OLD.target_language, OLD.source_revision_id,
           OLD.candidate_id, OLD.version_no, OLD.rollback_of_version_id, OLD.created_by_user_id,
           OLD.created_at, OLD.review_submission_id, OLD.review_snapshot_sha256
       ) AND NEW.superseded_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' AND OLD.review_submission_id IS NOT NULL AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'DELETE' AND OLD.review_submission_id IS NULL AND pg_trigger_depth() = 1 THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.review_submission_id IS NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Translation version history is immutable except for atomic supersession'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_translation_versions_history
    BEFORE UPDATE OR DELETE ON translation_versions
    FOR EACH ROW EXECUTE FUNCTION guard_translation_version_history();
CREATE TRIGGER trg_translation_versions_no_truncate
    BEFORE TRUNCATE ON translation_versions
    FOR EACH STATEMENT EXECUTE FUNCTION guard_translation_version_history();

--rollback DROP TRIGGER trg_translation_versions_history ON translation_versions;
--rollback DROP TRIGGER trg_translation_versions_no_truncate ON translation_versions;
--rollback DROP FUNCTION guard_translation_version_history();
--rollback DROP TRIGGER trg_translation_version_release_required ON translation_versions;
--rollback DROP FUNCTION require_translation_release_event();
--rollback DROP TRIGGER trg_translation_release_event_validate ON translation_release_events;
--rollback DROP FUNCTION validate_translation_release_event();
--rollback DROP TRIGGER trg_translation_release_events_immutable ON translation_release_events;
--rollback DROP TRIGGER trg_translation_release_events_no_truncate ON translation_release_events;
--rollback DROP FUNCTION guard_translation_release_event_immutable();
--rollback DROP TRIGGER trg_translation_version_segments_immutable ON translation_version_segments;
--rollback DROP TRIGGER trg_translation_version_segments_no_truncate ON translation_version_segments;
--rollback DROP FUNCTION guard_translation_version_segment_immutable();
