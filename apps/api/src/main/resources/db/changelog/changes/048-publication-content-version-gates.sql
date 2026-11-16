--liquibase formatted sql

--changeset novelweb:publication-054-content-version-columns-and-backfill splitStatements:true rollbackSplitStatements:true
ALTER TABLE works
    ADD COLUMN publication_content_version BIGINT NOT NULL DEFAULT 1
        CHECK (publication_content_version > 0);

ALTER TABLE publication_scope_access_approvals
    ADD COLUMN approved_content_version BIGINT NOT NULL DEFAULT 0
        CHECK (approved_content_version >= 0);

ALTER INDEX ix_publication_scope_approvals_current
    RENAME TO ix_publication_scope_approvals_current_old;
CREATE INDEX ix_publication_scope_approvals_current
    ON publication_scope_access_approvals (
        work_id, scope_code, approved_content_version, approved_at DESC, id DESC
    );
DROP INDEX ix_publication_scope_approvals_current_old;

ALTER TABLE publication_scope_access_approvals
    DISABLE TRIGGER trg_publication_scope_approval_immutable;
UPDATE publication_scope_access_approvals approval
   SET approved_content_version = work.publication_content_version
  FROM works work
 WHERE work.id = approval.work_id
   AND approval.approved_snapshot =
       publication_work_scope_snapshot(approval.work_id, approval.scope_code);
ALTER TABLE publication_scope_access_approvals
    ENABLE TRIGGER trg_publication_scope_approval_immutable;

--rollback DROP INDEX ix_publication_scope_approvals_current;
--rollback CREATE INDEX ix_publication_scope_approvals_current ON publication_scope_access_approvals (work_id, scope_code, approved_at DESC, id DESC);
--rollback ALTER TABLE publication_scope_access_approvals DROP COLUMN approved_content_version;
--rollback ALTER TABLE works DROP COLUMN publication_content_version;

--changeset novelweb:publication-055-content-version-triggers splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION guard_publication_work_content_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    publication_content_changed BOOLEAN;
BEGIN
    publication_content_changed :=
        ROW(
            NEW.owner_user_id,
            NEW.library_id,
            NEW.title,
            NEW.author,
            NEW.source_language,
            NEW.description,
            NEW.metadata_schema_version,
            NEW.metadata,
            NEW.authoring_metadata_version,
            NEW.source_kind,
            NEW.visibility,
            NEW.status,
            NEW.deleted_at
        ) IS DISTINCT FROM ROW(
            OLD.owner_user_id,
            OLD.library_id,
            OLD.title,
            OLD.author,
            OLD.source_language,
            OLD.description,
            OLD.metadata_schema_version,
            OLD.metadata,
            OLD.authoring_metadata_version,
            OLD.source_kind,
            OLD.visibility,
            OLD.status,
            OLD.deleted_at
        );

    IF publication_content_changed THEN
        NEW.publication_content_version := OLD.publication_content_version + 1;
    ELSIF NEW.publication_content_version IS DISTINCT FROM OLD.publication_content_version THEN
        IF pg_trigger_depth() <= 1
           OR NEW.publication_content_version <> OLD.publication_content_version + 1 THEN
            RAISE EXCEPTION 'Publication content versions are maintained by PostgreSQL'
                USING ERRCODE = '55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION publication_current_revision_work_ids(p_revision_ids UUID[])
RETURNS UUID[]
LANGUAGE SQL
STABLE
AS $$
    SELECT COALESCE(
        array_agg(DISTINCT chapter.work_id ORDER BY chapter.work_id),
        ARRAY[]::UUID[]
    )
    FROM chapters chapter
    WHERE chapter.source_revision_id = ANY(COALESCE(p_revision_ids, ARRAY[]::UUID[]))
      AND chapter.status = 'ready'
      AND chapter.deleted_at IS NULL;
$$;

CREATE FUNCTION lock_publication_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_ids UUID[] := ARRAY[]::UUID[];
    revision_ids UUID[] := ARRAY[]::UUID[];
    target_work_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'chapters' THEN
        IF TG_OP <> 'INSERT'
           AND OLD.status = 'ready' AND OLD.deleted_at IS NULL
           AND OLD.source_revision_id IS NOT NULL THEN
            work_ids := work_ids || ARRAY[OLD.work_id];
        END IF;
        IF TG_OP <> 'DELETE'
           AND NEW.status = 'ready' AND NEW.deleted_at IS NULL
           AND NEW.source_revision_id IS NOT NULL THEN
            work_ids := work_ids || ARRAY[NEW.work_id];
        END IF;
    ELSIF TG_TABLE_NAME = 'chapter_revisions' THEN
        IF TG_OP <> 'INSERT' THEN
            revision_ids := revision_ids || ARRAY[OLD.id];
        END IF;
        IF TG_OP <> 'DELETE' THEN
            revision_ids := revision_ids || ARRAY[NEW.id];
        END IF;
        work_ids := publication_current_revision_work_ids(revision_ids);
    ELSIF TG_TABLE_NAME = 'segments' THEN
        IF TG_OP <> 'INSERT' THEN
            revision_ids := revision_ids || ARRAY[OLD.chapter_revision_id];
        END IF;
        IF TG_OP <> 'DELETE' THEN
            revision_ids := revision_ids || ARRAY[NEW.chapter_revision_id];
        END IF;
        work_ids := publication_current_revision_work_ids(revision_ids);
    ELSE
        RAISE EXCEPTION 'Unsupported publication content table: %', TG_TABLE_NAME
            USING ERRCODE = '55000';
    END IF;

    FOR target_work_id IN
        SELECT DISTINCT candidate.work_id
          FROM unnest(work_ids) AS candidate(work_id)
         WHERE candidate.work_id IS NOT NULL
         ORDER BY candidate.work_id
    LOOP
        PERFORM work.id
          FROM works work
         WHERE work.id = target_work_id
         FOR UPDATE;
    END LOOP;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION bump_publication_content_version_after_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    content_changed BOOLEAN := FALSE;
    work_ids UUID[] := ARRAY[]::UUID[];
    revision_ids UUID[] := ARRAY[]::UUID[];
    target_work_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'chapters' THEN
        IF TG_OP = 'INSERT' THEN
            content_changed := NEW.status = 'ready'
                AND NEW.deleted_at IS NULL
                AND NEW.source_revision_id IS NOT NULL;
        ELSIF TG_OP = 'DELETE' THEN
            content_changed := OLD.status = 'ready'
                AND OLD.deleted_at IS NULL
                AND OLD.source_revision_id IS NOT NULL;
        ELSE
            content_changed :=
                (OLD.status = 'ready' AND OLD.deleted_at IS NULL AND OLD.source_revision_id IS NOT NULL)
                OR (NEW.status = 'ready' AND NEW.deleted_at IS NULL AND NEW.source_revision_id IS NOT NULL);
            content_changed := content_changed AND ROW(
                OLD.work_id, OLD.id, OLD.ordinal, OLD.title, OLD.source_language,
                OLD.source_revision_id, OLD.status, OLD.deleted_at
            ) IS DISTINCT FROM ROW(
                NEW.work_id, NEW.id, NEW.ordinal, NEW.title, NEW.source_language,
                NEW.source_revision_id, NEW.status, NEW.deleted_at
            );
        END IF;
        IF content_changed THEN
            IF TG_OP <> 'INSERT' AND OLD.status = 'ready'
               AND OLD.deleted_at IS NULL AND OLD.source_revision_id IS NOT NULL THEN
                work_ids := work_ids || ARRAY[OLD.work_id];
            END IF;
            IF TG_OP <> 'DELETE' AND NEW.status = 'ready'
               AND NEW.deleted_at IS NULL AND NEW.source_revision_id IS NOT NULL THEN
                work_ids := work_ids || ARRAY[NEW.work_id];
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'chapter_revisions' THEN
        IF TG_OP = 'INSERT' THEN
            content_changed := TRUE;
            revision_ids := ARRAY[NEW.id];
        ELSIF TG_OP = 'DELETE' THEN
            content_changed := TRUE;
            revision_ids := ARRAY[OLD.id];
        ELSE
            content_changed := ROW(
                OLD.id, OLD.chapter_id, OLD.revision_no, OLD.source_format,
                OLD.normalized_content, OLD.content_hash
            ) IS DISTINCT FROM ROW(
                NEW.id, NEW.chapter_id, NEW.revision_no, NEW.source_format,
                NEW.normalized_content, NEW.content_hash
            );
            revision_ids := ARRAY[OLD.id, NEW.id];
        END IF;
        IF content_changed THEN
            work_ids := publication_current_revision_work_ids(revision_ids);
        END IF;
    ELSIF TG_TABLE_NAME = 'segments' THEN
        IF TG_OP = 'INSERT' THEN
            content_changed := TRUE;
            revision_ids := ARRAY[NEW.chapter_revision_id];
        ELSIF TG_OP = 'DELETE' THEN
            content_changed := TRUE;
            revision_ids := ARRAY[OLD.chapter_revision_id];
        ELSE
            content_changed := ROW(
                OLD.chapter_revision_id, OLD.id, OLD.ordinal, OLD.segment_type,
                OLD.source_text, OLD.stable_anchor, OLD.checksum
            ) IS DISTINCT FROM ROW(
                NEW.chapter_revision_id, NEW.id, NEW.ordinal, NEW.segment_type,
                NEW.source_text, NEW.stable_anchor, NEW.checksum
            );
            revision_ids := ARRAY[OLD.chapter_revision_id, NEW.chapter_revision_id];
        END IF;
        IF content_changed THEN
            work_ids := publication_current_revision_work_ids(revision_ids);
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported publication content table: %', TG_TABLE_NAME
            USING ERRCODE = '55000';
    END IF;

    FOR target_work_id IN
        SELECT DISTINCT candidate.work_id
          FROM unnest(work_ids) AS candidate(work_id)
         WHERE candidate.work_id IS NOT NULL
         ORDER BY candidate.work_id
    LOOP
        UPDATE works
           SET publication_content_version = publication_content_version + 1
         WHERE id = target_work_id;
    END LOOP;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION reject_publication_content_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Publication content tables cannot be truncated'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_works_publication_content_version
    BEFORE UPDATE ON works
    FOR EACH ROW EXECUTE FUNCTION guard_publication_work_content_version();

CREATE TRIGGER trg_chapters_publication_content_lock
    BEFORE INSERT OR UPDATE OR DELETE ON chapters
    FOR EACH ROW EXECUTE FUNCTION lock_publication_content_mutation();
CREATE TRIGGER trg_chapters_publication_content_bump
    AFTER INSERT OR UPDATE OR DELETE ON chapters
    FOR EACH ROW EXECUTE FUNCTION bump_publication_content_version_after_mutation();
CREATE TRIGGER trg_chapters_publication_content_truncate
    BEFORE TRUNCATE ON chapters
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_content_truncate();

CREATE TRIGGER trg_chapter_revisions_publication_content_lock
    BEFORE INSERT OR UPDATE OR DELETE ON chapter_revisions
    FOR EACH ROW EXECUTE FUNCTION lock_publication_content_mutation();
CREATE TRIGGER trg_chapter_revisions_publication_content_bump
    AFTER INSERT OR UPDATE OR DELETE ON chapter_revisions
    FOR EACH ROW EXECUTE FUNCTION bump_publication_content_version_after_mutation();
CREATE TRIGGER trg_chapter_revisions_publication_content_truncate
    BEFORE TRUNCATE ON chapter_revisions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_content_truncate();

CREATE TRIGGER trg_segments_publication_content_lock
    BEFORE INSERT OR UPDATE OR DELETE ON segments
    FOR EACH ROW EXECUTE FUNCTION lock_publication_content_mutation();
CREATE TRIGGER trg_segments_publication_content_bump
    AFTER INSERT OR UPDATE OR DELETE ON segments
    FOR EACH ROW EXECUTE FUNCTION bump_publication_content_version_after_mutation();
CREATE TRIGGER trg_segments_publication_content_truncate
    BEFORE TRUNCATE ON segments
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_content_truncate();

CREATE TRIGGER trg_works_publication_content_truncate
    BEFORE TRUNCATE ON works
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_content_truncate();
--rollback DROP TRIGGER trg_works_publication_content_truncate ON works;
--rollback DROP TRIGGER trg_segments_publication_content_truncate ON segments;
--rollback DROP TRIGGER trg_segments_publication_content_bump ON segments;
--rollback DROP TRIGGER trg_segments_publication_content_lock ON segments;
--rollback DROP TRIGGER trg_chapter_revisions_publication_content_truncate ON chapter_revisions;
--rollback DROP TRIGGER trg_chapter_revisions_publication_content_bump ON chapter_revisions;
--rollback DROP TRIGGER trg_chapter_revisions_publication_content_lock ON chapter_revisions;
--rollback DROP TRIGGER trg_chapters_publication_content_truncate ON chapters;
--rollback DROP TRIGGER trg_chapters_publication_content_bump ON chapters;
--rollback DROP TRIGGER trg_chapters_publication_content_lock ON chapters;
--rollback DROP TRIGGER trg_works_publication_content_version ON works;
--rollback DROP FUNCTION reject_publication_content_truncate();
--rollback DROP FUNCTION bump_publication_content_version_after_mutation();
--rollback DROP FUNCTION lock_publication_content_mutation();
--rollback DROP FUNCTION publication_current_revision_work_ids(UUID[]);
--rollback DROP FUNCTION guard_publication_work_content_version();

--changeset novelweb:publication-056-versioned-current-scope-authorization splitStatements:false rollbackSplitStatements:false
CREATE OR REPLACE FUNCTION validate_publication_scope_access_approval()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_owner_id UUID;
    current_content_version BIGINT;
    current_policy_version VARCHAR(64);
    current_policy_sha256 BYTEA;
    reviewer_assignment RECORD;
    owner_accepted_policy BOOLEAN;
BEGIN
    SELECT owner_user_id, publication_content_version
      INTO work_owner_id, current_content_version
      FROM works
     WHERE id = NEW.work_id
       AND source_kind = 'authored'
       AND status = 'active'
       AND deleted_at IS NULL
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A current authored work is required for publication approval'
            USING ERRCODE = '23514';
    END IF;
    IF work_owner_id = NEW.approved_by_user_id THEN
        RAISE EXCEPTION 'A work owner cannot approve their own publication'
            USING ERRCODE = '23514';
    END IF;

    SELECT assignment.assigned_at, assignment.revoked_at
      INTO reviewer_assignment
      FROM security_role_assignments assignment
      JOIN users reviewer ON reviewer.id = assignment.user_id AND reviewer.status = 'active'
     WHERE assignment.user_id = NEW.approved_by_user_id
       AND assignment.role_code = 'publication_reviewer'
       AND assignment.assigned_at <= NEW.approved_at
       AND (assignment.revoked_at IS NULL OR assignment.revoked_at > NEW.approved_at)
     ORDER BY assignment.assigned_at DESC, assignment.id DESC
     LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'An assigned publication reviewer is required'
            USING ERRCODE = '23514';
    END IF;

    SELECT version, policy_sha256
      INTO current_policy_version, current_policy_sha256
      FROM publication_rights_policy_documents
     WHERE status = 'APPROVED'
       AND effective_at <= NEW.approved_at
     ORDER BY effective_at DESC, version DESC
     LIMIT 1;
    IF NOT FOUND
       OR NEW.policy_version IS DISTINCT FROM current_policy_version
       OR NEW.policy_sha256 IS DISTINCT FROM current_policy_sha256 THEN
        RAISE EXCEPTION 'The current approved rights policy is required'
            USING ERRCODE = '23514';
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM publication_rights_policy_acceptances acceptance
         WHERE acceptance.user_id = work_owner_id
           AND acceptance.work_id = NEW.work_id
           AND acceptance.policy_version = NEW.policy_version
           AND acceptance.policy_sha256 = NEW.policy_sha256
           AND acceptance.accepted_at <= NEW.approved_at
    ) INTO owner_accepted_policy;
    IF NOT owner_accepted_policy THEN
        RAISE EXCEPTION 'The work owner must accept the current rights policy before review'
            USING ERRCODE = '23514';
    END IF;

    IF publication_work_scope_snapshot(NEW.work_id, NEW.scope_code) IS NULL
       OR NEW.approved_snapshot IS DISTINCT FROM
            publication_work_scope_snapshot(NEW.work_id, NEW.scope_code) THEN
        RAISE EXCEPTION 'The approved work snapshot is stale or unsupported'
            USING ERRCODE = '23514';
    END IF;
    NEW.approved_content_version := current_content_version;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION publication_scope_access_is_current(p_work_id UUID, p_scope_code VARCHAR)
RETURNS BOOLEAN
LANGUAGE SQL
VOLATILE
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM works w
          JOIN libraries l
            ON l.id = w.library_id
           AND l.owner_user_id = w.owner_user_id
           AND l.deleted_at IS NULL
          JOIN users owner
            ON owner.id = w.owner_user_id
           AND owner.status = 'active'
          JOIN publication_scope_access_approvals a
            ON a.work_id = w.id
           AND a.scope_code = p_scope_code
           AND a.approved_content_version = w.publication_content_version
          JOIN publication_rights_policy_documents policy
            ON policy.version = a.policy_version
           AND policy.policy_sha256 = a.policy_sha256
           AND policy.status = 'APPROVED'
           AND policy.effective_at <= clock_timestamp()
         WHERE w.id = p_work_id
           AND w.source_kind = 'authored'
           AND w.visibility = 'public'
           AND w.status = 'active'
           AND w.deleted_at IS NULL
           AND a.approved_at <= clock_timestamp()
           AND (a.expires_at IS NULL OR a.expires_at > clock_timestamp())
           AND a.policy_version = (
                SELECT current_policy.version
                  FROM publication_rights_policy_documents current_policy
                 WHERE current_policy.status = 'APPROVED'
                   AND current_policy.effective_at <= clock_timestamp()
                 ORDER BY current_policy.effective_at DESC, current_policy.version DESC
                 LIMIT 1
           )
           AND NOT EXISTS (
                SELECT 1
                  FROM publication_scope_access_revocations revoked
                 WHERE revoked.approval_id = a.id
           )
    );
$$;

--rollback CREATE OR REPLACE FUNCTION publication_scope_access_is_current(p_work_id UUID, p_scope_code VARCHAR) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$ SELECT EXISTS (SELECT 1 FROM works w JOIN libraries l ON l.id = w.library_id AND l.owner_user_id = w.owner_user_id AND l.deleted_at IS NULL JOIN users owner ON owner.id = w.owner_user_id AND owner.status = 'active' JOIN publication_scope_access_approvals a ON a.work_id = w.id AND a.scope_code = p_scope_code JOIN publication_rights_policy_documents policy ON policy.version = a.policy_version AND policy.policy_sha256 = a.policy_sha256 AND policy.status = 'APPROVED' AND policy.effective_at <= clock_timestamp() WHERE w.id = p_work_id AND w.source_kind = 'authored' AND w.visibility = 'public' AND w.status = 'active' AND w.deleted_at IS NULL AND a.approved_at <= clock_timestamp() AND (a.expires_at IS NULL OR a.expires_at > clock_timestamp()) AND a.approved_snapshot = publication_work_scope_snapshot(w.id, a.scope_code) AND a.policy_version = (SELECT current_policy.version FROM publication_rights_policy_documents current_policy WHERE current_policy.status = 'APPROVED' AND current_policy.effective_at <= clock_timestamp() ORDER BY current_policy.effective_at DESC, current_policy.version DESC LIMIT 1) AND NOT EXISTS (SELECT 1 FROM publication_scope_access_revocations revoked WHERE revoked.approval_id = a.id)); $$;
--rollback CREATE OR REPLACE FUNCTION validate_publication_scope_access_approval() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE work_owner_id UUID; current_policy_version VARCHAR(64); current_policy_sha256 BYTEA; reviewer_assignment RECORD; owner_accepted_policy BOOLEAN; BEGIN SELECT owner_user_id INTO work_owner_id FROM works WHERE id = NEW.work_id AND source_kind = 'authored' AND status = 'active' AND deleted_at IS NULL; IF NOT FOUND THEN RAISE EXCEPTION 'A current authored work is required for publication approval' USING ERRCODE = '23514'; END IF; IF work_owner_id = NEW.approved_by_user_id THEN RAISE EXCEPTION 'A work owner cannot approve their own publication' USING ERRCODE = '23514'; END IF; SELECT assignment.assigned_at, assignment.revoked_at INTO reviewer_assignment FROM security_role_assignments assignment JOIN users reviewer ON reviewer.id = assignment.user_id AND reviewer.status = 'active' WHERE assignment.user_id = NEW.approved_by_user_id AND assignment.role_code = 'publication_reviewer' AND assignment.assigned_at <= NEW.approved_at AND (assignment.revoked_at IS NULL OR assignment.revoked_at > NEW.approved_at) ORDER BY assignment.assigned_at DESC, assignment.id DESC LIMIT 1; IF NOT FOUND THEN RAISE EXCEPTION 'An assigned publication reviewer is required' USING ERRCODE = '23514'; END IF; SELECT version, policy_sha256 INTO current_policy_version, current_policy_sha256 FROM publication_rights_policy_documents WHERE status = 'APPROVED' AND effective_at <= NEW.approved_at ORDER BY effective_at DESC, version DESC LIMIT 1; IF NOT FOUND OR NEW.policy_version IS DISTINCT FROM current_policy_version OR NEW.policy_sha256 IS DISTINCT FROM current_policy_sha256 THEN RAISE EXCEPTION 'The current approved rights policy is required' USING ERRCODE = '23514'; END IF; SELECT EXISTS (SELECT 1 FROM publication_rights_policy_acceptances acceptance WHERE acceptance.user_id = work_owner_id AND acceptance.work_id = NEW.work_id AND acceptance.policy_version = NEW.policy_version AND acceptance.policy_sha256 = NEW.policy_sha256 AND acceptance.accepted_at <= NEW.approved_at) INTO owner_accepted_policy; IF NOT owner_accepted_policy THEN RAISE EXCEPTION 'The work owner must accept the current rights policy before review' USING ERRCODE = '23514'; END IF; IF publication_work_scope_snapshot(NEW.work_id, NEW.scope_code) IS NULL OR NEW.approved_snapshot IS DISTINCT FROM publication_work_scope_snapshot(NEW.work_id, NEW.scope_code) THEN RAISE EXCEPTION 'The approved work snapshot is stale or unsupported' USING ERRCODE = '23514'; END IF; RETURN NEW; END; $$;
