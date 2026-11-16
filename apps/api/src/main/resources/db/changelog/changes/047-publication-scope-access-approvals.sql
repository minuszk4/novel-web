--liquibase formatted sql

--changeset novelweb:publication-047-scope-work-snapshot splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION publication_work_scope_snapshot(p_work_id UUID, p_scope_code VARCHAR)
RETURNS JSONB
LANGUAGE SQL
STABLE
AS $$
    SELECT CASE
        WHEN p_scope_code NOT IN ('PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE') THEN NULL
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
                  AND c.status = 'ready'
                  AND c.deleted_at IS NULL
                  AND c.source_revision_id IS NOT NULL
            )
        )
    END
    FROM works w
    WHERE w.id = p_work_id;
$$;
-- Supported current snapshots: PUBLIC_METADATA, CHAPTER_TEXT and COMMERCIAL_SALE.
-- Public reads intentionally remain denied until a reviewer creates an exact approved snapshot.
--rollback DROP FUNCTION publication_work_scope_snapshot(UUID, VARCHAR);

--changeset novelweb:publication-048-scope-access-approvals
CREATE TABLE publication_scope_access_approvals (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    scope_code VARCHAR(32) NOT NULL
        CHECK (scope_code IN (
            'PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE'
        )),
    approved_snapshot JSONB NOT NULL CHECK (jsonb_typeof(approved_snapshot) = 'object'),
    policy_version VARCHAR(64) NOT NULL,
    policy_sha256 BYTEA NOT NULL CHECK (octet_length(policy_sha256) = 32),
    approved_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason_code VARCHAR(40) NOT NULL CHECK (reason_code IN ('RIGHTS_REVIEWED', 'SCOPE_CONFIRMED')),
    approved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ,
    CONSTRAINT fk_publication_scope_approval_policy
        FOREIGN KEY (policy_version, policy_sha256)
        REFERENCES publication_rights_policy_documents(version, policy_sha256) ON DELETE RESTRICT,
    CHECK (expires_at IS NULL OR expires_at > approved_at),
    CHECK (approved_at <= clock_timestamp())
);
CREATE INDEX ix_publication_scope_approvals_current
    ON publication_scope_access_approvals (work_id, scope_code, approved_at DESC, id DESC);
--rollback DROP INDEX ix_publication_scope_approvals_current;
--rollback DROP TABLE publication_scope_access_approvals;

--changeset novelweb:publication-049-scope-revocations
CREATE TABLE publication_scope_access_revocations (
    approval_id UUID PRIMARY KEY
        REFERENCES publication_scope_access_approvals(id) ON DELETE CASCADE,
    revoked_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason_code VARCHAR(40) NOT NULL
        CHECK (reason_code IN (
            'RIGHTS_WITHDRAWN', 'REVIEW_INVALIDATED', 'POLICY_CHANGED', 'EMERGENCY_TAKEDOWN'
        )),
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (revoked_at <= clock_timestamp())
);
--rollback DROP TABLE publication_scope_access_revocations;

--changeset novelweb:publication-050-scope-approval-validation splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION validate_publication_scope_access_approval()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_owner_id UUID;
    current_policy_version VARCHAR(64);
    current_policy_sha256 BYTEA;
    reviewer_assignment RECORD;
    owner_accepted_policy BOOLEAN;
BEGIN
    SELECT owner_user_id
      INTO work_owner_id
      FROM works
     WHERE id = NEW.work_id
       AND source_kind = 'authored'
       AND status = 'active'
       AND deleted_at IS NULL;
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
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_publication_scope_access_approval_validate
    BEFORE INSERT ON publication_scope_access_approvals
    FOR EACH ROW EXECUTE FUNCTION validate_publication_scope_access_approval();
--rollback DROP TRIGGER trg_publication_scope_access_approval_validate ON publication_scope_access_approvals;
--rollback DROP FUNCTION validate_publication_scope_access_approval();

--changeset novelweb:publication-051-scope-revocation-validation splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION validate_publication_scope_access_revocation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_owner_id UUID;
BEGIN
    SELECT w.owner_user_id
      INTO work_owner_id
      FROM publication_scope_access_approvals a
      JOIN works w ON w.id = a.work_id
     WHERE a.id = NEW.approval_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'The publication approval does not exist'
            USING ERRCODE = '23503';
    END IF;
    IF work_owner_id = NEW.revoked_by_user_id THEN
        RAISE EXCEPTION 'A work owner cannot revoke a reviewer decision'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM security_role_assignments r
          JOIN users actor ON actor.id = r.user_id AND actor.status = 'active'
         WHERE r.user_id = NEW.revoked_by_user_id
           AND r.role_code IN ('publication_reviewer', 'system_admin')
           AND r.assigned_at <= NEW.revoked_at
           AND (r.revoked_at IS NULL OR r.revoked_at > NEW.revoked_at)
    ) THEN
        RAISE EXCEPTION 'An assigned reviewer or system administrator is required to revoke approval'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_publication_scope_access_revocation_validate
    BEFORE INSERT ON publication_scope_access_revocations
    FOR EACH ROW EXECUTE FUNCTION validate_publication_scope_access_revocation();
--rollback DROP TRIGGER trg_publication_scope_access_revocation_validate ON publication_scope_access_revocations;
--rollback DROP FUNCTION validate_publication_scope_access_revocation();

--changeset novelweb:publication-052-scope-audit-immutability splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION guard_publication_scope_access_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Publication approval and revocation records are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION reject_publication_scope_access_audit_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Publication approval and revocation records cannot be truncated'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_publication_scope_approval_immutable
    BEFORE UPDATE OR DELETE ON publication_scope_access_approvals
    FOR EACH ROW EXECUTE FUNCTION guard_publication_scope_access_audit_mutation();
CREATE TRIGGER trg_publication_scope_approval_truncate_immutable
    BEFORE TRUNCATE ON publication_scope_access_approvals
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_scope_access_audit_truncate();
CREATE TRIGGER trg_publication_scope_revocation_immutable
    BEFORE UPDATE OR DELETE ON publication_scope_access_revocations
    FOR EACH ROW EXECUTE FUNCTION guard_publication_scope_access_audit_mutation();
CREATE TRIGGER trg_publication_scope_revocation_truncate_immutable
    BEFORE TRUNCATE ON publication_scope_access_revocations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_scope_access_audit_truncate();
--rollback DROP TRIGGER trg_publication_scope_revocation_truncate_immutable ON publication_scope_access_revocations;
--rollback DROP TRIGGER trg_publication_scope_revocation_immutable ON publication_scope_access_revocations;
--rollback DROP TRIGGER trg_publication_scope_approval_truncate_immutable ON publication_scope_access_approvals;
--rollback DROP TRIGGER trg_publication_scope_approval_immutable ON publication_scope_access_approvals;
--rollback DROP FUNCTION reject_publication_scope_access_audit_truncate();
--rollback DROP FUNCTION guard_publication_scope_access_audit_mutation();

--changeset novelweb:publication-053-current-scope-authorization splitStatements:false rollbackSplitStatements:false
CREATE FUNCTION publication_scope_access_is_current(p_work_id UUID, p_scope_code VARCHAR)
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
           AND a.approved_snapshot = publication_work_scope_snapshot(w.id, a.scope_code)
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
--rollback DROP FUNCTION publication_scope_access_is_current(UUID, VARCHAR);
