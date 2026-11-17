--liquibase formatted sql

--changeset novelweb:translation-066-review-decisions
ALTER TABLE translation_candidate_review_submissions
    DROP CONSTRAINT translation_candidate_review_submissions_status_check;
ALTER TABLE translation_candidate_review_submissions
    ADD CONSTRAINT ck_translation_candidate_review_submission_status
        CHECK (status IN ('pending_review', 'accepted', 'rejected'));

CREATE TABLE translation_candidate_review_decisions (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE
        REFERENCES translation_candidate_review_submissions(id) ON DELETE CASCADE,
    reviewer_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reviewer_assignment_id UUID NOT NULL
        REFERENCES security_role_assignments(id) ON DELETE CASCADE,
    action_code VARCHAR(16) NOT NULL CHECK (action_code IN ('ACCEPT', 'REJECT')),
    reason_code VARCHAR(40),
    snapshot_sha256 BYTEA NOT NULL CHECK (octet_length(snapshot_sha256) = 32),
    idempotency_key_sha256 BYTEA NOT NULL
        CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_translation_candidate_review_decision_reason CHECK (
        (action_code = 'ACCEPT' AND (reason_code IS NULL OR reason_code IN (
            'ACCURATE', 'MINOR_TERMINOLOGY_ISSUE'
        ))) OR
        (action_code = 'REJECT' AND reason_code IN (
            'MISSING_CONTENT', 'MEANING_ERROR', 'TERMINOLOGY_ERROR', 'FORMATTING_ERROR', 'OTHER'
        ))
    ),
    CONSTRAINT uq_translation_candidate_review_decision_key
        UNIQUE (reviewer_user_id, submission_id, idempotency_key_sha256)
);
CREATE INDEX ix_translation_candidate_review_decisions_actor
    ON translation_candidate_review_decisions (reviewer_user_id, decided_at DESC, id DESC);

--rollback DROP INDEX ix_translation_candidate_review_decisions_actor;
--rollback DROP TABLE translation_candidate_review_decisions;
--rollback ALTER TABLE translation_candidate_review_submissions DROP CONSTRAINT ck_translation_candidate_review_submission_status;
--rollback ALTER TABLE translation_candidate_review_submissions ADD CONSTRAINT translation_candidate_review_submissions_status_check CHECK (status = 'pending_review');

--changeset novelweb:translation-067-review-snapshot-decision-transition splitStatements:false rollbackSplitStatements:false
CREATE OR REPLACE FUNCTION guard_translation_candidate_review_submission_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_action VARCHAR(16);
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Translation review submissions are append-only'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        expected_action := CASE NEW.status
            WHEN 'accepted' THEN 'ACCEPT'
            WHEN 'rejected' THEN 'REJECT'
            ELSE NULL
        END;
        IF TG_TABLE_NAME = 'translation_candidate_review_submissions'
           AND OLD.status = 'pending_review'
           AND expected_action IS NOT NULL
           AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status')
           AND EXISTS (
               SELECT 1
                 FROM translation_candidate_review_decisions decision
                WHERE decision.submission_id = OLD.id
                  AND decision.action_code = expected_action
                  AND decision.snapshot_sha256 = OLD.snapshot_sha256
           )
        THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Translation review snapshots are immutable except for a recorded decision transition'
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

    RETURN NEW;
END;
$$;
--rollback CREATE OR REPLACE FUNCTION guard_translation_candidate_review_submission_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE library_id UUID; BEGIN IF TG_OP = 'TRUNCATE' THEN RAISE EXCEPTION 'Translation review submissions are append-only' USING ERRCODE = '55000'; END IF; IF TG_OP = 'INSERT' THEN IF TG_TABLE_NAME <> 'translation_candidate_review_submissions' THEN RETURN NEW; END IF; PERFORM id FROM users WHERE id = NEW.submitted_by_user_id AND status = 'active' FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'An active submission actor is required' USING ERRCODE = '23514'; END IF; PERFORM id FROM translation_jobs WHERE id = NEW.job_id AND provider_id = 'fake-local' AND status = 'review_required' AND source_revision_id = NEW.source_revision_id AND source_sha256 = NEW.source_sha256 FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'A current fake-local review-required job is required' USING ERRCODE = '23514'; END IF; PERFORM id FROM chapters WHERE id = NEW.chapter_id AND work_id = NEW.work_id AND status = 'ready' AND source_revision_id = NEW.source_revision_id FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'The submitted translation source is stale' USING ERRCODE = '23514'; END IF; PERFORM id FROM translation_candidates WHERE id = NEW.candidate_id AND job_id = NEW.job_id AND work_id = NEW.work_id AND chapter_id = NEW.chapter_id AND target_language = NEW.target_language AND source_revision_id = NEW.source_revision_id AND source_sha256 = NEW.source_sha256 AND status = 'ready_for_review' FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'A current complete translation candidate is required' USING ERRCODE = '23514'; END IF; SELECT library.id INTO library_id FROM works work JOIN libraries library ON library.id = work.library_id WHERE work.id = NEW.work_id AND work.deleted_at IS NULL AND library.deleted_at IS NULL; IF NOT FOUND OR NOT can_access_library(library_id, NEW.submitted_by_user_id, TRUE) THEN RAISE EXCEPTION 'Current translation write access is required' USING ERRCODE = '23514'; END IF; RETURN NEW; END IF; IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'Translation review submissions are immutable' USING ERRCODE = '55000'; END IF; IF TG_OP = 'DELETE' THEN IF pg_trigger_depth() > 1 THEN RETURN OLD; END IF; RAISE EXCEPTION 'Translation review submissions are immutable' USING ERRCODE = '55000'; END IF; RETURN NULL; END; $$;

--changeset novelweb:translation-068-review-decision-guard splitStatements:false
CREATE FUNCTION guard_translation_candidate_review_decision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    submission translation_candidate_review_submissions%ROWTYPE;
    owner_user_id UUID;
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Translation review decisions are append-only'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT *
          INTO submission
          FROM translation_candidate_review_submissions
         WHERE id = NEW.submission_id
         FOR UPDATE;
        IF NOT FOUND
           OR submission.status <> 'pending_review'
           OR NEW.snapshot_sha256 <> submission.snapshot_sha256
        THEN
            RAISE EXCEPTION 'A pending current translation review snapshot is required'
                USING ERRCODE = '23514';
        END IF;

        SELECT work.owner_user_id
          INTO owner_user_id
          FROM works work
          JOIN chapters chapter ON chapter.work_id = work.id
          JOIN chapter_revisions revision
            ON revision.id = submission.source_revision_id
           AND revision.chapter_id = submission.chapter_id
         WHERE work.id = submission.work_id
           AND work.status = 'active'
           AND work.visibility = 'private'
           AND work.deleted_at IS NULL
           AND chapter.id = submission.chapter_id
           AND chapter.status = 'ready'
           AND chapter.source_revision_id = submission.source_revision_id
           AND revision.content_hash = submission.source_sha256;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'The submitted translation source is stale or unavailable'
                USING ERRCODE = '23514';
        END IF;

        PERFORM assignment.id
          FROM security_role_assignments assignment
          JOIN users reviewer
            ON reviewer.id = assignment.user_id
           AND reviewer.status = 'active'
         WHERE assignment.id = NEW.reviewer_assignment_id
           AND assignment.user_id = NEW.reviewer_user_id
           AND assignment.role_code = 'publication_reviewer'
           AND assignment.assigned_at <= NEW.decided_at
           AND (assignment.revoked_at IS NULL OR assignment.revoked_at > NEW.decided_at)
         FOR SHARE OF assignment, reviewer;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'An active assigned reviewer is required'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.reviewer_user_id = owner_user_id
           OR NEW.reviewer_user_id = submission.submitted_by_user_id
           OR EXISTS (
               SELECT 1
                 FROM translation_candidate_review_submission_segments snapshot_segment
                 JOIN translation_candidate_segment_edits edit
                   ON edit.candidate_id = snapshot_segment.candidate_id
                  AND edit.source_segment_id = snapshot_segment.source_segment_id
                  AND edit.edit_revision = snapshot_segment.edit_revision
                WHERE snapshot_segment.submission_id = submission.id
                  AND edit.editor_user_id = NEW.reviewer_user_id
           )
        THEN
            RAISE EXCEPTION 'A snapshot owner, submitter or contributor cannot review it'
                USING ERRCODE = '23514';
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Translation review decisions are immutable'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_translation_candidate_review_decision_guard
    BEFORE INSERT OR UPDATE OR DELETE ON translation_candidate_review_decisions
    FOR EACH ROW EXECUTE FUNCTION guard_translation_candidate_review_decision_mutation();
CREATE TRIGGER trg_translation_candidate_review_decision_truncate_guard
    BEFORE TRUNCATE ON translation_candidate_review_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION guard_translation_candidate_review_decision_mutation();
--rollback DROP TRIGGER trg_translation_candidate_review_decision_truncate_guard ON translation_candidate_review_decisions;
--rollback DROP TRIGGER trg_translation_candidate_review_decision_guard ON translation_candidate_review_decisions;
--rollback DROP FUNCTION guard_translation_candidate_review_decision_mutation();

--changeset novelweb:translation-069-review-decision-status-trigger splitStatements:false
CREATE FUNCTION apply_translation_candidate_review_decision_status()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    next_status VARCHAR(24);
BEGIN
    next_status := CASE NEW.action_code
        WHEN 'ACCEPT' THEN 'accepted'
        WHEN 'REJECT' THEN 'rejected'
    END;
    UPDATE translation_candidate_review_submissions
       SET status = next_status
     WHERE id = NEW.submission_id
       AND status = 'pending_review';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Translation review decision lost its pending-state race'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_translation_candidate_review_apply_decision_status
    AFTER INSERT ON translation_candidate_review_decisions
    FOR EACH ROW EXECUTE FUNCTION apply_translation_candidate_review_decision_status();
--rollback DROP TRIGGER trg_translation_candidate_review_apply_decision_status ON translation_candidate_review_decisions;
--rollback DROP FUNCTION apply_translation_candidate_review_decision_status();
