--liquibase formatted sql

--changeset novelweb:imports-044-rights-statements
CREATE TABLE import_rights_statements (
    version VARCHAR(64) PRIMARY KEY
        CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    effective_at TIMESTAMPTZ NOT NULL,
    display_text TEXT NOT NULL
        CHECK (length(btrim(display_text)) > 0)
        CHECK (octet_length(display_text) <= 32768),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'APPROVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    approved_at TIMESTAMPTZ,
    approved_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    CHECK ((status = 'DRAFT' AND approved_at IS NULL AND approved_by IS NULL)
        OR (status = 'APPROVED' AND approved_at IS NOT NULL AND approved_by IS NOT NULL))
);
--rollback DROP TABLE import_rights_statements;

--changeset novelweb:imports-045-current-rights-statement-index
CREATE INDEX ix_import_rights_statements_current
    ON import_rights_statements (effective_at DESC, version DESC)
    WHERE status = 'APPROVED';
--rollback DROP INDEX ix_import_rights_statements_current;

--changeset novelweb:imports-046-rights-acceptances
CREATE TABLE import_rights_acceptances (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key UUID NOT NULL,
    statement_version VARCHAR(64) NOT NULL
        REFERENCES import_rights_statements(version) ON DELETE RESTRICT,
    statement_sha256 BYTEA NOT NULL CHECK (octet_length(statement_sha256) = 32),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    consumed_at TIMESTAMPTZ,
    source VARCHAR(32) NOT NULL CHECK (source = 'web-import-flow'),
    CONSTRAINT uq_import_rights_acceptance_actor_key UNIQUE (user_id, idempotency_key),
    CHECK (consumed_at IS NULL OR consumed_at >= accepted_at)
);
--rollback DROP TABLE import_rights_acceptances;

--changeset novelweb:imports-047-acceptance-history-index
CREATE INDEX ix_import_rights_acceptances_user_time
    ON import_rights_acceptances (user_id, accepted_at DESC);
--rollback DROP INDEX ix_import_rights_acceptances_user_time;

--changeset novelweb:imports-048-approved-statement-immutability splitStatements:false
CREATE FUNCTION reject_approved_import_rights_statement_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'APPROVED' THEN
        RAISE EXCEPTION 'Approved import-rights statements are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
--rollback DROP FUNCTION reject_approved_import_rights_statement_mutation();

--changeset novelweb:imports-049-approved-statement-immutability-trigger
CREATE TRIGGER trg_import_rights_statement_immutable
    BEFORE UPDATE OR DELETE ON import_rights_statements
    FOR EACH ROW EXECUTE FUNCTION reject_approved_import_rights_statement_mutation();
--rollback DROP TRIGGER trg_import_rights_statement_immutable ON import_rights_statements;

--changeset novelweb:imports-050-acceptance-audit-immutability splitStatements:false
CREATE FUNCTION guard_import_rights_acceptance_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Import-rights acceptance audit records are append-only'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.statement_version IS DISTINCT FROM OLD.statement_version
       OR NEW.statement_sha256 IS DISTINCT FROM OLD.statement_sha256
       OR NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
       OR NEW.source IS DISTINCT FROM OLD.source
       OR OLD.consumed_at IS NOT NULL
       OR NEW.consumed_at IS NULL THEN
        RAISE EXCEPTION 'Import-rights acceptance audit fields are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
--rollback DROP FUNCTION guard_import_rights_acceptance_mutation();

--changeset novelweb:imports-051-acceptance-audit-trigger
CREATE TRIGGER trg_import_rights_acceptance_immutable
    BEFORE UPDATE OR DELETE ON import_rights_acceptances
    FOR EACH ROW EXECUTE FUNCTION guard_import_rights_acceptance_mutation();
--rollback DROP TRIGGER trg_import_rights_acceptance_immutable ON import_rights_acceptances;
