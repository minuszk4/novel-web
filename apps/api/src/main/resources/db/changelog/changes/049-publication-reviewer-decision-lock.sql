--liquibase formatted sql

--changeset novelweb:publication-057-reviewer-decision-serialization splitStatements:false rollbackSplitStatements:false
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
    -- The decision timestamp is server-owned; callers cannot backdate a review.
    NEW.approved_at := clock_timestamp();

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

    SELECT assignment.assigned_at
      INTO reviewer_assignment
      FROM security_role_assignments assignment
      JOIN users reviewer ON reviewer.id = assignment.user_id AND reviewer.status = 'active'
     WHERE assignment.user_id = NEW.approved_by_user_id
       AND assignment.role_code = 'publication_reviewer'
       AND assignment.assigned_at <= NEW.approved_at
       AND assignment.revoked_at IS NULL
     ORDER BY assignment.assigned_at DESC, assignment.id DESC
     LIMIT 1
     FOR UPDATE OF assignment;
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

--rollback CREATE OR REPLACE FUNCTION validate_publication_scope_access_approval() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE work_owner_id UUID; current_content_version BIGINT; current_policy_version VARCHAR(64); current_policy_sha256 BYTEA; reviewer_assignment RECORD; owner_accepted_policy BOOLEAN; BEGIN SELECT owner_user_id, publication_content_version INTO work_owner_id, current_content_version FROM works WHERE id = NEW.work_id AND source_kind = 'authored' AND status = 'active' AND deleted_at IS NULL FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'A current authored work is required for publication approval' USING ERRCODE = '23514'; END IF; IF work_owner_id = NEW.approved_by_user_id THEN RAISE EXCEPTION 'A work owner cannot approve their own publication' USING ERRCODE = '23514'; END IF; SELECT assignment.assigned_at, assignment.revoked_at INTO reviewer_assignment FROM security_role_assignments assignment JOIN users reviewer ON reviewer.id = assignment.user_id AND reviewer.status = 'active' WHERE assignment.user_id = NEW.approved_by_user_id AND assignment.role_code = 'publication_reviewer' AND assignment.assigned_at <= NEW.approved_at AND (assignment.revoked_at IS NULL OR assignment.revoked_at > NEW.approved_at) ORDER BY assignment.assigned_at DESC, assignment.id DESC LIMIT 1; IF NOT FOUND THEN RAISE EXCEPTION 'An assigned publication reviewer is required' USING ERRCODE = '23514'; END IF; SELECT version, policy_sha256 INTO current_policy_version, current_policy_sha256 FROM publication_rights_policy_documents WHERE status = 'APPROVED' AND effective_at <= NEW.approved_at ORDER BY effective_at DESC, version DESC LIMIT 1; IF NOT FOUND OR NEW.policy_version IS DISTINCT FROM current_policy_version OR NEW.policy_sha256 IS DISTINCT FROM current_policy_sha256 THEN RAISE EXCEPTION 'The current approved rights policy is required' USING ERRCODE = '23514'; END IF; SELECT EXISTS (SELECT 1 FROM publication_rights_policy_acceptances acceptance WHERE acceptance.user_id = work_owner_id AND acceptance.work_id = NEW.work_id AND acceptance.policy_version = NEW.policy_version AND acceptance.policy_sha256 = NEW.policy_sha256 AND acceptance.accepted_at <= NEW.approved_at) INTO owner_accepted_policy; IF NOT owner_accepted_policy THEN RAISE EXCEPTION 'The work owner must accept the current rights policy before review' USING ERRCODE = '23514'; END IF; IF publication_work_scope_snapshot(NEW.work_id, NEW.scope_code) IS NULL OR NEW.approved_snapshot IS DISTINCT FROM publication_work_scope_snapshot(NEW.work_id, NEW.scope_code) THEN RAISE EXCEPTION 'The approved work snapshot is stale or unsupported' USING ERRCODE = '23514'; END IF; NEW.approved_content_version := current_content_version; RETURN NEW; END; $$;
