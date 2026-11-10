--liquibase formatted sql

--changeset novelweb:imports-052-staging-jobs
ALTER TABLE import_rights_acceptances
    ADD CONSTRAINT uq_import_rights_acceptance_actor_id UNIQUE (user_id, id);

CREATE TABLE import_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    library_id UUID NOT NULL REFERENCES libraries(id) ON DELETE RESTRICT,
    rights_acceptance_id UUID NOT NULL UNIQUE,
    rights_statement_version VARCHAR(64) NOT NULL
        REFERENCES import_rights_statements(version) ON DELETE RESTRICT,
    original_filename VARCHAR(255) NOT NULL
        CHECK (length(btrim(original_filename)) BETWEEN 1 AND 255),
    declared_content_type VARCHAR(160),
    declared_size_bytes BIGINT NOT NULL CHECK (declared_size_bytes > 0),
    storage_key VARCHAR(80) NOT NULL UNIQUE
        CHECK (storage_key ~ '^staging/[0-9a-f-]{36}\.upload$'),
    upload_token_sha256 BYTEA CHECK (upload_token_sha256 IS NULL OR octet_length(upload_token_sha256) = 32),
    upload_token_expires_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL DEFAULT 'upload_pending'
        CHECK (status IN ('upload_pending', 'uploaded', 'failed', 'cancelled', 'expired')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    rights_confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ,
    actual_size_bytes BIGINT,
    content_sha256 BYTEA CHECK (content_sha256 IS NULL OR octet_length(content_sha256) = 32),
    CONSTRAINT fk_import_jobs_actor_receipt
        FOREIGN KEY (user_id, rights_acceptance_id)
        REFERENCES import_rights_acceptances(user_id, id) ON DELETE RESTRICT,
    CHECK (expires_at > created_at),
    CHECK (upload_token_expires_at IS NULL OR upload_token_expires_at <= expires_at),
    CHECK (
        (status = 'upload_pending'
            AND upload_token_sha256 IS NOT NULL
            AND upload_token_expires_at IS NOT NULL
            AND uploaded_at IS NULL
            AND actual_size_bytes IS NULL
            AND content_sha256 IS NULL)
        OR (status = 'uploaded'
            AND upload_token_sha256 IS NULL
            AND upload_token_expires_at IS NULL
            AND uploaded_at IS NOT NULL
            AND actual_size_bytes IS NOT NULL
            AND actual_size_bytes > 0
            AND content_sha256 IS NOT NULL)
        OR (status IN ('failed', 'cancelled', 'expired')
            AND upload_token_sha256 IS NULL
            AND upload_token_expires_at IS NULL)
    )
);
--rollback DROP TABLE import_jobs;
--rollback ALTER TABLE import_rights_acceptances DROP CONSTRAINT uq_import_rights_acceptance_actor_id;

--changeset novelweb:imports-053-staging-job-indexes
CREATE INDEX ix_import_jobs_actor_created
    ON import_jobs (user_id, created_at DESC, id DESC);
CREATE INDEX ix_import_jobs_expiry
    ON import_jobs (expires_at)
    WHERE status IN ('upload_pending', 'uploaded');
--rollback DROP INDEX ix_import_jobs_expiry;
--rollback DROP INDEX ix_import_jobs_actor_created;

--changeset novelweb:imports-054-staging-job-transition-guard splitStatements:false
CREATE FUNCTION guard_import_job_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.library_id IS DISTINCT FROM OLD.library_id
       OR NEW.rights_acceptance_id IS DISTINCT FROM OLD.rights_acceptance_id
       OR NEW.rights_statement_version IS DISTINCT FROM OLD.rights_statement_version
       OR NEW.original_filename IS DISTINCT FROM OLD.original_filename
       OR NEW.declared_content_type IS DISTINCT FROM OLD.declared_content_type
       OR NEW.declared_size_bytes IS DISTINCT FROM OLD.declared_size_bytes
       OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.rights_confirmed_at IS DISTINCT FROM OLD.rights_confirmed_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'Import job identity and audit fields are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'upload_pending' THEN
        RAISE EXCEPTION 'Only pending import jobs may change state'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'upload_pending' THEN
        IF NEW.upload_token_sha256 IS NULL OR NEW.upload_token_expires_at IS NULL
           OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
           OR NEW.actual_size_bytes IS DISTINCT FROM OLD.actual_size_bytes
           OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 THEN
            RAISE EXCEPTION 'Pending import target rotation is invalid'
                USING ERRCODE = '55000';
        END IF;
    ELSIF NEW.status = 'uploaded' THEN
        IF NEW.upload_token_sha256 IS NOT NULL OR NEW.upload_token_expires_at IS NOT NULL
           OR NEW.uploaded_at IS NULL OR NEW.actual_size_bytes IS NULL OR NEW.content_sha256 IS NULL THEN
            RAISE EXCEPTION 'Completed import upload is missing server verification data'
                USING ERRCODE = '55000';
        END IF;
    ELSIF NEW.status IN ('failed', 'cancelled', 'expired') THEN
        IF NEW.upload_token_sha256 IS NOT NULL OR NEW.upload_token_expires_at IS NOT NULL THEN
            RAISE EXCEPTION 'Terminal import jobs cannot retain an upload token'
                USING ERRCODE = '55000';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported import job state transition'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;
--rollback DROP FUNCTION guard_import_job_update();

--changeset novelweb:imports-055-staging-job-transition-trigger
CREATE TRIGGER trg_import_job_update_guard
    BEFORE UPDATE ON import_jobs
    FOR EACH ROW EXECUTE FUNCTION guard_import_job_update();
--rollback DROP TRIGGER trg_import_job_update_guard ON import_jobs;
