--liquibase formatted sql

--changeset novelweb:publication-001-rights-policy-documents
CREATE TABLE publication_rights_policy_documents (
    version VARCHAR(64) PRIMARY KEY
        CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    effective_at TIMESTAMPTZ NOT NULL,
    display_text TEXT NOT NULL
        CHECK (length(btrim(display_text)) > 0)
        CHECK (octet_length(display_text) <= 32768),
    policy_sha256 BYTEA NOT NULL CHECK (octet_length(policy_sha256) = 32),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'APPROVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    approved_at TIMESTAMPTZ,
    approved_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE (version, policy_sha256),
    CHECK ((status = 'DRAFT' AND approved_at IS NULL AND approved_by IS NULL)
        OR (status = 'APPROVED' AND approved_at IS NOT NULL AND approved_by IS NOT NULL))
);
--rollback DROP TABLE publication_rights_policy_documents;

--changeset novelweb:publication-002-current-rights-policy-index
CREATE INDEX ix_publication_rights_policy_documents_current
    ON publication_rights_policy_documents (effective_at DESC, version DESC)
    WHERE status = 'APPROVED';
--rollback DROP INDEX ix_publication_rights_policy_documents_current;

--changeset novelweb:publication-003-rights-policy-immutability-function splitStatements:false
CREATE FUNCTION guard_publication_rights_policy_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Publication-rights policy documents cannot be truncated'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Publication-rights policies must be created as drafts'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'APPROVED' THEN
            RAISE EXCEPTION 'Approved publication-rights policies are immutable'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status = 'APPROVED' THEN
        RAISE EXCEPTION 'Approved publication-rights policies are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'APPROVED'
       AND (NEW.version IS DISTINCT FROM OLD.version
            OR NEW.effective_at IS DISTINCT FROM OLD.effective_at
            OR NEW.display_text IS DISTINCT FROM OLD.display_text
            OR NEW.policy_sha256 IS DISTINCT FROM OLD.policy_sha256
            OR NEW.created_at IS DISTINCT FROM OLD.created_at) THEN
        RAISE EXCEPTION 'A publication-rights policy snapshot cannot change while being approved'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
--rollback DROP FUNCTION guard_publication_rights_policy_mutation();

--changeset novelweb:publication-004-rights-policy-immutability-triggers
CREATE TRIGGER trg_publication_rights_policy_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON publication_rights_policy_documents
    FOR EACH ROW EXECUTE FUNCTION guard_publication_rights_policy_mutation();
CREATE TRIGGER trg_publication_rights_policy_truncate_immutable
    BEFORE TRUNCATE ON publication_rights_policy_documents
    FOR EACH STATEMENT EXECUTE FUNCTION guard_publication_rights_policy_mutation();
--rollback DROP TRIGGER trg_publication_rights_policy_truncate_immutable ON publication_rights_policy_documents;
--rollback DROP TRIGGER trg_publication_rights_policy_immutable ON publication_rights_policy_documents;

--changeset novelweb:publication-005-rights-policy-acceptances
CREATE TABLE publication_rights_policy_acceptances (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    work_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    policy_sha256 BYTEA NOT NULL CHECK (octet_length(policy_sha256) = 32),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    source VARCHAR(40) NOT NULL CHECK (source = 'web-authoring-publication'),
    CONSTRAINT uq_publication_rights_acceptance_idempotency
        UNIQUE (user_id, work_id, idempotency_key),
    CONSTRAINT uq_publication_rights_acceptance_policy
        UNIQUE (user_id, work_id, policy_version),
    CONSTRAINT fk_publication_rights_acceptance_owner_work
        FOREIGN KEY (user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_rights_acceptance_authored_work
        FOREIGN KEY (work_id) REFERENCES authoring_manuscript_creations(work_id) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_rights_acceptance_policy_snapshot
        FOREIGN KEY (policy_version, policy_sha256)
        REFERENCES publication_rights_policy_documents(version, policy_sha256) ON DELETE RESTRICT
);
--rollback DROP TABLE publication_rights_policy_acceptances;

--changeset novelweb:publication-006-rights-acceptance-history-indexes
CREATE INDEX ix_publication_rights_acceptances_user_time
    ON publication_rights_policy_acceptances (user_id, accepted_at DESC, id DESC);
CREATE INDEX ix_publication_rights_acceptances_work_time
    ON publication_rights_policy_acceptances (work_id, accepted_at DESC, id DESC);
--rollback DROP INDEX ix_publication_rights_acceptances_work_time;
--rollback DROP INDEX ix_publication_rights_acceptances_user_time;

--changeset novelweb:publication-007-rights-acceptance-policy-check-function splitStatements:false
CREATE FUNCTION validate_publication_rights_policy_acceptance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_status VARCHAR(16);
    current_effective_at TIMESTAMPTZ;
BEGIN
    SELECT status, effective_at
      INTO current_status, current_effective_at
      FROM publication_rights_policy_documents
     WHERE version = NEW.policy_version
       AND policy_sha256 = NEW.policy_sha256
     FOR KEY SHARE;

    IF NOT FOUND OR current_status <> 'APPROVED' OR current_effective_at > NEW.accepted_at THEN
        RAISE EXCEPTION 'A currently effective approved publication-rights policy is required'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.accepted_at > clock_timestamp() THEN
        RAISE EXCEPTION 'Publication-rights policy acceptance cannot be future-dated'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
--rollback DROP FUNCTION validate_publication_rights_policy_acceptance();

--changeset novelweb:publication-008-rights-acceptance-policy-check-trigger
CREATE TRIGGER trg_publication_rights_acceptance_policy_check
    BEFORE INSERT ON publication_rights_policy_acceptances
    FOR EACH ROW EXECUTE FUNCTION validate_publication_rights_policy_acceptance();
--rollback DROP TRIGGER trg_publication_rights_acceptance_policy_check ON publication_rights_policy_acceptances;

--changeset novelweb:publication-009-rights-acceptance-immutability-function splitStatements:false
CREATE FUNCTION reject_publication_rights_acceptance_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Publication-rights policy acceptance records are append-only'
        USING ERRCODE = '55000';
END;
$$;
--rollback DROP FUNCTION reject_publication_rights_acceptance_mutation();

--changeset novelweb:publication-010-rights-acceptance-immutability-triggers
CREATE TRIGGER trg_publication_rights_acceptance_immutable
    BEFORE UPDATE OR DELETE ON publication_rights_policy_acceptances
    FOR EACH ROW EXECUTE FUNCTION reject_publication_rights_acceptance_mutation();
CREATE TRIGGER trg_publication_rights_acceptance_truncate_immutable
    BEFORE TRUNCATE ON publication_rights_policy_acceptances
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_rights_acceptance_mutation();
--rollback DROP TRIGGER trg_publication_rights_acceptance_truncate_immutable ON publication_rights_policy_acceptances;
--rollback DROP TRIGGER trg_publication_rights_acceptance_immutable ON publication_rights_policy_acceptances;
