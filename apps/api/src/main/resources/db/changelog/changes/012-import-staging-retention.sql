--liquibase formatted sql

--changeset novelweb:imports-056-staging-file-cleanup-marker
ALTER TABLE import_jobs
    ADD COLUMN staging_file_deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_import_jobs_staging_file_deleted_terminal
        CHECK (staging_file_deleted_at IS NULL OR status IN ('failed', 'cancelled', 'expired'));
--rollback ALTER TABLE import_jobs DROP CONSTRAINT ck_import_jobs_staging_file_deleted_terminal, DROP COLUMN staging_file_deleted_at;

--changeset novelweb:imports-057-staging-cleanup-retry-index
CREATE INDEX ix_import_jobs_staging_cleanup_retry
    ON import_jobs (expires_at, id)
    WHERE status IN ('failed', 'cancelled', 'expired') AND staging_file_deleted_at IS NULL;
--rollback DROP INDEX ix_import_jobs_staging_cleanup_retry;

--changeset novelweb:imports-058-import-job-expiry-transition-guard splitStatements:false
CREATE FUNCTION guard_import_job_retention_update()
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

    IF NEW.staging_file_deleted_at IS DISTINCT FROM OLD.staging_file_deleted_at THEN
        IF OLD.status NOT IN ('failed', 'cancelled', 'expired')
           OR OLD.staging_file_deleted_at IS NOT NULL
           OR NEW.staging_file_deleted_at IS NULL
           OR NEW.status IS DISTINCT FROM OLD.status
           OR NEW.upload_token_sha256 IS DISTINCT FROM OLD.upload_token_sha256
           OR NEW.upload_token_expires_at IS DISTINCT FROM OLD.upload_token_expires_at
           OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
           OR NEW.actual_size_bytes IS DISTINCT FROM OLD.actual_size_bytes
           OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 THEN
            RAISE EXCEPTION 'Staging cleanup marker may only be set once on a terminal import job'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'uploaded' THEN
        IF NEW.status <> 'expired'
           OR OLD.expires_at > clock_timestamp()
           OR NEW.upload_token_sha256 IS NOT NULL
           OR NEW.upload_token_expires_at IS NOT NULL
           OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
           OR NEW.actual_size_bytes IS DISTINCT FROM OLD.actual_size_bytes
           OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 THEN
            RAISE EXCEPTION 'Uploaded imports may only expire after their retention deadline'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'upload_pending' THEN
        RAISE EXCEPTION 'Only pending imports or retained uploaded imports may change state'
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
--rollback DROP FUNCTION guard_import_job_retention_update();

--changeset novelweb:imports-059-staging-cleanup-trigger
DROP TRIGGER trg_import_job_update_guard ON import_jobs;
CREATE TRIGGER trg_import_job_update_guard
    BEFORE UPDATE ON import_jobs
    FOR EACH ROW EXECUTE FUNCTION guard_import_job_retention_update();
--rollback DROP TRIGGER trg_import_job_update_guard ON import_jobs;
--rollback CREATE TRIGGER trg_import_job_update_guard BEFORE UPDATE ON import_jobs FOR EACH ROW EXECUTE FUNCTION guard_import_job_update();
