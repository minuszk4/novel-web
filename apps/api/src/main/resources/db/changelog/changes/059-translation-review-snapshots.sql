--liquibase formatted sql

--changeset novelweb:translation-059-candidate-review-snapshots
CREATE TABLE translation_candidate_review_submissions (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL,
    job_id UUID NOT NULL,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    target_language VARCHAR(35) NOT NULL
        CHECK (target_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    source_revision_id UUID NOT NULL,
    source_sha256 BYTEA NOT NULL CHECK (octet_length(source_sha256) = 32),
    snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(snapshot_sha256) = 32),
    segment_count INTEGER NOT NULL CHECK (segment_count > 0),
    submitted_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key_sha256 BYTEA NOT NULL
        CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    status VARCHAR(24) NOT NULL DEFAULT 'pending_review'
        CHECK (status = 'pending_review'),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_translation_review_submission_candidate_snapshot
        UNIQUE (candidate_id, snapshot_sha256),
    CONSTRAINT uq_translation_review_submission_id_candidate
        UNIQUE (id, candidate_id),
    CONSTRAINT uq_translation_review_submission_idempotency
        UNIQUE (submitted_by_user_id, candidate_id, idempotency_key_sha256),
    CONSTRAINT fk_translation_review_submission_candidate
        FOREIGN KEY (candidate_id, job_id, work_id, chapter_id, target_language, source_revision_id)
        REFERENCES translation_candidates
            (id, job_id, work_id, chapter_id, target_language, source_revision_id)
        ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_translation_review_submission_pending
    ON translation_candidate_review_submissions (candidate_id)
    WHERE status = 'pending_review';
CREATE INDEX ix_translation_review_submission_submitter
    ON translation_candidate_review_submissions (submitted_by_user_id, submitted_at DESC, id DESC);

CREATE TABLE translation_candidate_review_submission_segments (
    submission_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    source_segment_id UUID NOT NULL,
    segment_ordinal INTEGER NOT NULL CHECK (segment_ordinal > 0),
    edit_revision INTEGER NOT NULL CHECK (edit_revision > 0),
    translated_sha256 BYTEA NOT NULL CHECK (octet_length(translated_sha256) = 32),
    PRIMARY KEY (submission_id, source_segment_id),
    CONSTRAINT uq_translation_review_submission_segment_ordinal
        UNIQUE (submission_id, segment_ordinal),
    CONSTRAINT fk_translation_review_submission_segment_submission
        FOREIGN KEY (submission_id, candidate_id)
        REFERENCES translation_candidate_review_submissions (id, candidate_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_translation_review_submission_segment_candidate
        FOREIGN KEY (candidate_id, source_segment_id)
        REFERENCES translation_candidate_segments (candidate_id, source_segment_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_translation_review_submission_segment_edit
        FOREIGN KEY (candidate_id, source_segment_id, edit_revision)
        REFERENCES translation_candidate_segment_edits
            (candidate_id, source_segment_id, edit_revision)
        ON DELETE CASCADE
);
CREATE INDEX ix_translation_review_submission_segments_order
    ON translation_candidate_review_submission_segments (submission_id, segment_ordinal);
--rollback DROP INDEX ix_translation_review_submission_segments_order;
--rollback DROP TABLE translation_candidate_review_submission_segments;
--rollback DROP INDEX ix_translation_review_submission_submitter;
--rollback DROP INDEX uq_translation_review_submission_pending;
--rollback DROP TABLE translation_candidate_review_submissions;

--changeset novelweb:translation-060-candidate-review-submission-guard splitStatements:false
CREATE FUNCTION guard_translation_candidate_review_submission_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    library_id UUID;
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Translation review submissions are append-only'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF TG_TABLE_NAME <> 'translation_candidate_review_submissions' THEN
            RETURN NEW;
        END IF;

        PERFORM id
          FROM users
         WHERE id = NEW.submitted_by_user_id
           AND status = 'active'
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'An active submission actor is required'
                USING ERRCODE = '23514';
        END IF;

        PERFORM id
          FROM translation_jobs
         WHERE id = NEW.job_id
           AND provider_id = 'fake-local'
           AND status = 'review_required'
           AND source_revision_id = NEW.source_revision_id
           AND source_sha256 = NEW.source_sha256
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'A current fake-local review-required job is required'
                USING ERRCODE = '23514';
        END IF;

        PERFORM id
          FROM chapters
         WHERE id = NEW.chapter_id
           AND work_id = NEW.work_id
           AND status = 'ready'
           AND source_revision_id = NEW.source_revision_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'The submitted translation source is stale'
                USING ERRCODE = '23514';
        END IF;

        PERFORM id
          FROM translation_candidates
         WHERE id = NEW.candidate_id
           AND job_id = NEW.job_id
           AND work_id = NEW.work_id
           AND chapter_id = NEW.chapter_id
           AND target_language = NEW.target_language
           AND source_revision_id = NEW.source_revision_id
           AND source_sha256 = NEW.source_sha256
           AND status = 'ready_for_review'
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'A current complete translation candidate is required'
                USING ERRCODE = '23514';
        END IF;

        SELECT library.id
          INTO library_id
          FROM works work
          JOIN libraries library ON library.id = work.library_id
         WHERE work.id = NEW.work_id
           AND work.deleted_at IS NULL
           AND library.deleted_at IS NULL;
        IF NOT FOUND OR NOT can_access_library(library_id, NEW.submitted_by_user_id, TRUE) THEN
            RAISE EXCEPTION 'Current translation write access is required'
                USING ERRCODE = '23514';
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Translation review submissions are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        -- Retention and account erasure may cascade-delete the private snapshot.
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Translation review submissions are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NULL;
END;
$$;
--rollback DROP FUNCTION guard_translation_candidate_review_submission_mutation();

--changeset novelweb:translation-061-candidate-review-submission-guard-triggers
CREATE TRIGGER trg_translation_candidate_review_submission_guard
    BEFORE INSERT OR UPDATE OR DELETE ON translation_candidate_review_submissions
    FOR EACH ROW EXECUTE FUNCTION guard_translation_candidate_review_submission_mutation();
CREATE TRIGGER trg_translation_candidate_review_submission_segment_guard
    BEFORE INSERT OR UPDATE OR DELETE ON translation_candidate_review_submission_segments
    FOR EACH ROW EXECUTE FUNCTION guard_translation_candidate_review_submission_mutation();
CREATE TRIGGER trg_translation_candidate_review_submission_truncate_guard
    BEFORE TRUNCATE ON translation_candidate_review_submissions
    FOR EACH STATEMENT EXECUTE FUNCTION guard_translation_candidate_review_submission_mutation();
CREATE TRIGGER trg_translation_candidate_review_submission_segment_truncate_guard
    BEFORE TRUNCATE ON translation_candidate_review_submission_segments
    FOR EACH STATEMENT EXECUTE FUNCTION guard_translation_candidate_review_submission_mutation();
--rollback DROP TRIGGER trg_translation_candidate_review_submission_segment_truncate_guard ON translation_candidate_review_submission_segments;
--rollback DROP TRIGGER trg_translation_candidate_review_submission_truncate_guard ON translation_candidate_review_submissions;
--rollback DROP TRIGGER trg_translation_candidate_review_submission_segment_guard ON translation_candidate_review_submission_segments;
--rollback DROP TRIGGER trg_translation_candidate_review_submission_guard ON translation_candidate_review_submissions;

--changeset novelweb:translation-062-candidate-review-snapshot-completeness splitStatements:false
CREATE FUNCTION validate_translation_candidate_review_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    submission_id UUID;
    submission translation_candidate_review_submissions%ROWTYPE;
    candidate_segment_count INTEGER;
    source_segment_count INTEGER;
    snapshot_segment_count INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'translation_candidate_review_submissions' THEN
        submission_id := NEW.id;
    ELSE
        submission_id := NEW.submission_id;
    END IF;

    SELECT * INTO submission
      FROM translation_candidate_review_submissions
     WHERE id = submission_id;
    IF NOT FOUND THEN
        -- Parent cleanup/account-erasure cascades remove the whole snapshot.
        RETURN NULL;
    END IF;

    SELECT count(*) INTO candidate_segment_count
      FROM translation_candidate_segments
     WHERE candidate_id = submission.candidate_id;
    SELECT count(*) INTO source_segment_count
      FROM translation_unit_segments
     WHERE job_id = submission.job_id
       AND source_revision_id = submission.source_revision_id;
    SELECT count(*) INTO snapshot_segment_count
      FROM translation_candidate_review_submission_segments snapshot_segment
     WHERE snapshot_segment.submission_id = submission.id;

    IF candidate_segment_count <> submission.segment_count
       OR source_segment_count <> submission.segment_count
       OR snapshot_segment_count <> submission.segment_count
    THEN
        RAISE EXCEPTION 'Every source segment must have one manually edited snapshot row'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM translation_candidate_review_submission_segments snapshot_segment
          JOIN translation_unit_segments unit_segment
            ON unit_segment.job_id = submission.job_id
           AND unit_segment.source_revision_id = submission.source_revision_id
           AND unit_segment.source_segment_id = snapshot_segment.source_segment_id
          JOIN translation_candidate_segment_edits edit
            ON edit.candidate_id = snapshot_segment.candidate_id
           AND edit.source_segment_id = snapshot_segment.source_segment_id
           AND edit.edit_revision = snapshot_segment.edit_revision
         WHERE snapshot_segment.submission_id = submission.id
           AND (
               snapshot_segment.segment_ordinal <> unit_segment.segment_ordinal
               OR snapshot_segment.translated_sha256 <> edit.translated_sha256
               OR edit.edit_revision <> (
                   SELECT max(latest.edit_revision)
                     FROM translation_candidate_segment_edits latest
                    WHERE latest.candidate_id = snapshot_segment.candidate_id
                      AND latest.source_segment_id = snapshot_segment.source_segment_id
               )
           )
    ) THEN
        RAISE EXCEPTION 'Submitted translation snapshot must use the current ordered manual edits'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM translation_jobs job
          JOIN chapters chapter
            ON chapter.id = job.chapter_id
           AND chapter.work_id = job.work_id
          JOIN chapter_revisions revision
            ON revision.id = job.source_revision_id
           AND revision.chapter_id = job.chapter_id
         WHERE job.id = submission.job_id
           AND job.provider_id = 'fake-local'
           AND job.status = 'review_required'
           AND chapter.status = 'ready'
           AND chapter.source_revision_id = submission.source_revision_id
           AND revision.content_hash = submission.source_sha256
    ) THEN
        RAISE EXCEPTION 'Submitted translation source is no longer current'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;
--rollback DROP FUNCTION validate_translation_candidate_review_snapshot();

--changeset novelweb:translation-064-candidate-review-snapshot-prune-function splitStatements:false
CREATE FUNCTION prune_translation_candidate_review_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Run after the contributing child row has been deleted. Deleting its parent
    -- from a BEFORE trigger makes PostgreSQL cascade back into the row currently
    -- being removed and fails with a tuple-modified error.
    IF EXISTS (
        SELECT 1
          FROM translation_candidate_review_submissions submission
         WHERE submission.id = OLD.submission_id
    ) THEN
        DELETE FROM translation_candidate_review_submissions
         WHERE id = OLD.submission_id;
    END IF;
    RETURN NULL;
END;
$$;
--rollback DROP FUNCTION prune_translation_candidate_review_snapshot();

--changeset novelweb:translation-065-candidate-review-snapshot-prune-trigger
CREATE TRIGGER trg_translation_candidate_review_snapshot_prune
    AFTER DELETE ON translation_candidate_review_submission_segments
    FOR EACH ROW EXECUTE FUNCTION prune_translation_candidate_review_snapshot();
--rollback DROP TRIGGER trg_translation_candidate_review_snapshot_prune ON translation_candidate_review_submission_segments;

--changeset novelweb:translation-063-candidate-review-snapshot-completeness-triggers
CREATE CONSTRAINT TRIGGER trg_translation_candidate_review_snapshot_complete
    AFTER INSERT ON translation_candidate_review_submissions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_translation_candidate_review_snapshot();
--rollback DROP TRIGGER trg_translation_candidate_review_snapshot_complete ON translation_candidate_review_submissions;
