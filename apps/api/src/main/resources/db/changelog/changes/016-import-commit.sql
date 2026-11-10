--liquibase formatted sql

--changeset novelweb:imports-066-commit-state
ALTER TABLE import_jobs
    DROP CONSTRAINT import_jobs_status_check,
    ADD CONSTRAINT import_jobs_status_check
        CHECK (status IN ('upload_pending', 'uploaded', 'failed', 'cancelled', 'expired', 'committed')),
    DROP CONSTRAINT ck_import_jobs_staging_file_deleted_terminal,
    ADD CONSTRAINT ck_import_jobs_staging_file_deleted_terminal
        CHECK (staging_file_deleted_at IS NULL OR status IN ('failed', 'cancelled', 'expired', 'committed')),
    DROP CONSTRAINT import_jobs_check2,
    ADD CONSTRAINT ck_import_job_status_data
        CHECK (
            (status = 'upload_pending'
                AND upload_token_sha256 IS NOT NULL AND upload_token_expires_at IS NOT NULL
                AND uploaded_at IS NULL AND actual_size_bytes IS NULL AND content_sha256 IS NULL)
            OR (status IN ('uploaded', 'committed')
                AND upload_token_sha256 IS NULL AND upload_token_expires_at IS NULL
                AND uploaded_at IS NOT NULL AND actual_size_bytes IS NOT NULL
                AND actual_size_bytes > 0 AND content_sha256 IS NOT NULL)
            OR (status IN ('failed', 'cancelled', 'expired')
                AND upload_token_sha256 IS NULL AND upload_token_expires_at IS NULL)
        ),
    ADD CONSTRAINT uq_import_jobs_actor_id UNIQUE (user_id, id);

DROP INDEX ix_import_jobs_staging_cleanup_retry;
CREATE INDEX ix_import_jobs_staging_cleanup_retry
    ON import_jobs (expires_at, id)
    WHERE status IN ('failed', 'cancelled', 'expired', 'committed') AND staging_file_deleted_at IS NULL;

ALTER TABLE import_parse_jobs
    DROP CONSTRAINT import_parse_jobs_status_check,
    ADD CONSTRAINT import_parse_jobs_status_check
        CHECK (status IN ('queued', 'parsing', 'ready_to_commit', 'failed', 'committed')),
    DROP CONSTRAINT import_parse_jobs_check,
    ADD CONSTRAINT ck_import_parse_job_state
        CHECK (
            (status = 'queued'
                AND attempt = 0 AND preview_data IS NULL AND preview_etag IS NULL
                AND failure_code IS NULL AND lease_expires_at IS NULL AND completed_at IS NULL)
            OR (status = 'parsing'
                AND attempt > 0 AND preview_data IS NULL AND preview_etag IS NULL
                AND failure_code IS NULL AND lease_expires_at IS NOT NULL AND completed_at IS NULL)
            OR (status = 'ready_to_commit'
                AND attempt > 0 AND preview_data IS NOT NULL AND preview_etag IS NOT NULL
                AND failure_code IS NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL)
            OR (status = 'failed'
                AND attempt > 0 AND preview_data IS NULL AND preview_etag IS NULL
                AND failure_code IS NOT NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL)
            OR (status = 'committed'
                AND attempt > 0 AND preview_data IS NULL AND preview_etag IS NULL
                AND failure_code IS NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL)
        ),
    DROP CONSTRAINT ck_import_parse_preview_version_state,
    ADD CONSTRAINT ck_import_parse_preview_version_state
        CHECK ((status = 'ready_to_commit' AND preview_version >= 1)
            OR (status <> 'ready_to_commit' AND preview_version IS NULL));
--rollback ALTER TABLE import_parse_jobs DROP CONSTRAINT ck_import_parse_preview_version_state, ADD CONSTRAINT ck_import_parse_preview_version_state CHECK ((status = 'ready_to_commit' AND preview_version >= 1) OR (status <> 'ready_to_commit' AND preview_version IS NULL)), DROP CONSTRAINT ck_import_parse_job_state, ADD CONSTRAINT import_parse_jobs_check CHECK ((status = 'queued' AND attempt = 0 AND preview_data IS NULL AND preview_etag IS NULL AND failure_code IS NULL AND lease_expires_at IS NULL AND completed_at IS NULL) OR (status = 'parsing' AND attempt > 0 AND preview_data IS NULL AND preview_etag IS NULL AND failure_code IS NULL AND lease_expires_at IS NOT NULL AND completed_at IS NULL) OR (status = 'ready_to_commit' AND attempt > 0 AND preview_data IS NOT NULL AND preview_etag IS NOT NULL AND failure_code IS NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL) OR (status = 'failed' AND attempt > 0 AND preview_data IS NULL AND preview_etag IS NULL AND failure_code IS NOT NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL)), DROP CONSTRAINT import_parse_jobs_status_check, ADD CONSTRAINT import_parse_jobs_status_check CHECK (status IN ('queued', 'parsing', 'ready_to_commit', 'failed'));
--rollback DROP INDEX ix_import_jobs_staging_cleanup_retry;
--rollback CREATE INDEX ix_import_jobs_staging_cleanup_retry ON import_jobs (expires_at, id) WHERE status IN ('failed', 'cancelled', 'expired') AND staging_file_deleted_at IS NULL;
--rollback ALTER TABLE import_jobs DROP CONSTRAINT uq_import_jobs_actor_id, DROP CONSTRAINT ck_import_job_status_data, ADD CONSTRAINT import_jobs_check2 CHECK ((status = 'upload_pending' AND upload_token_sha256 IS NOT NULL AND upload_token_expires_at IS NOT NULL AND uploaded_at IS NULL AND actual_size_bytes IS NULL AND content_sha256 IS NULL) OR (status = 'uploaded' AND upload_token_sha256 IS NULL AND upload_token_expires_at IS NULL AND uploaded_at IS NOT NULL AND actual_size_bytes IS NOT NULL AND actual_size_bytes > 0 AND content_sha256 IS NOT NULL) OR (status IN ('failed', 'cancelled', 'expired') AND upload_token_sha256 IS NULL AND upload_token_expires_at IS NULL)), DROP CONSTRAINT ck_import_jobs_staging_file_deleted_terminal, ADD CONSTRAINT ck_import_jobs_staging_file_deleted_terminal CHECK (staging_file_deleted_at IS NULL OR status IN ('failed', 'cancelled', 'expired')), DROP CONSTRAINT import_jobs_status_check, ADD CONSTRAINT import_jobs_status_check CHECK (status IN ('upload_pending', 'uploaded', 'failed', 'cancelled', 'expired'));

--changeset novelweb:imports-067-commit-transition-guards splitStatements:false rollbackSplitStatements:false
CREATE OR REPLACE FUNCTION guard_import_job_retention_update()
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
        IF OLD.status NOT IN ('failed', 'cancelled', 'expired', 'committed')
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
        IF NEW.status NOT IN ('expired', 'committed')
           OR (NEW.status = 'expired' AND OLD.expires_at > clock_timestamp())
           OR NEW.upload_token_sha256 IS NOT NULL
           OR NEW.upload_token_expires_at IS NOT NULL
           OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
           OR NEW.actual_size_bytes IS DISTINCT FROM OLD.actual_size_bytes
           OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 THEN
            RAISE EXCEPTION 'Uploaded imports may only commit or expire after their retention deadline'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'upload_pending' THEN
        RAISE EXCEPTION 'Only pending or uploaded imports may change state'
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

--rollback CREATE OR REPLACE FUNCTION guard_import_job_retention_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id IS DISTINCT FROM OLD.id OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.library_id IS DISTINCT FROM OLD.library_id OR NEW.rights_acceptance_id IS DISTINCT FROM OLD.rights_acceptance_id OR NEW.rights_statement_version IS DISTINCT FROM OLD.rights_statement_version OR NEW.original_filename IS DISTINCT FROM OLD.original_filename OR NEW.declared_content_type IS DISTINCT FROM OLD.declared_content_type OR NEW.declared_size_bytes IS DISTINCT FROM OLD.declared_size_bytes OR NEW.storage_key IS DISTINCT FROM OLD.storage_key OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.rights_confirmed_at IS DISTINCT FROM OLD.rights_confirmed_at OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN RAISE EXCEPTION 'Import job identity and audit fields are immutable' USING ERRCODE = '55000'; END IF; IF NEW.staging_file_deleted_at IS DISTINCT FROM OLD.staging_file_deleted_at THEN IF OLD.status NOT IN ('failed', 'cancelled', 'expired') OR OLD.staging_file_deleted_at IS NOT NULL OR NEW.staging_file_deleted_at IS NULL OR NEW.status IS DISTINCT FROM OLD.status OR NEW.upload_token_sha256 IS DISTINCT FROM OLD.upload_token_sha256 OR NEW.upload_token_expires_at IS DISTINCT FROM OLD.upload_token_expires_at OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at OR NEW.actual_size_bytes IS DISTINCT FROM OLD.actual_size_bytes OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 THEN RAISE EXCEPTION 'Staging cleanup marker may only be set once on a terminal import job' USING ERRCODE = '55000'; END IF; RETURN NEW; END IF; IF OLD.status = 'uploaded' THEN IF NEW.status <> 'expired' OR OLD.expires_at > clock_timestamp() OR NEW.upload_token_sha256 IS NOT NULL OR NEW.upload_token_expires_at IS NOT NULL OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at OR NEW.actual_size_bytes IS DISTINCT FROM OLD.actual_size_bytes OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 THEN RAISE EXCEPTION 'Uploaded imports may only expire after their retention deadline' USING ERRCODE = '55000'; END IF; RETURN NEW; END IF; IF OLD.status <> 'upload_pending' THEN RAISE EXCEPTION 'Only pending imports or retained uploaded imports may change state' USING ERRCODE = '55000'; END IF; IF NEW.status = 'upload_pending' THEN IF NEW.upload_token_sha256 IS NULL OR NEW.upload_token_expires_at IS NULL OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at OR NEW.actual_size_bytes IS DISTINCT FROM OLD.actual_size_bytes OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 THEN RAISE EXCEPTION 'Pending import target rotation is invalid' USING ERRCODE = '55000'; END IF; ELSIF NEW.status = 'uploaded' THEN IF NEW.upload_token_sha256 IS NOT NULL OR NEW.upload_token_expires_at IS NOT NULL OR NEW.uploaded_at IS NULL OR NEW.actual_size_bytes IS NULL OR NEW.content_sha256 IS NULL THEN RAISE EXCEPTION 'Completed import upload is missing server verification data' USING ERRCODE = '55000'; END IF; ELSIF NEW.status IN ('failed', 'cancelled', 'expired') THEN IF NEW.upload_token_sha256 IS NOT NULL OR NEW.upload_token_expires_at IS NOT NULL THEN RAISE EXCEPTION 'Terminal import jobs cannot retain an upload token' USING ERRCODE = '55000'; END IF; ELSE RAISE EXCEPTION 'Unsupported import job state transition' USING ERRCODE = '55000'; END IF; RETURN NEW; END; $$;

--changeset novelweb:imports-068-parse-job-commit-transition-guard splitStatements:false rollbackSplitStatements:false
CREATE OR REPLACE FUNCTION guard_import_parse_job_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.import_id IS DISTINCT FROM OLD.import_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.parser_version IS DISTINCT FROM OLD.parser_version THEN
        RAISE EXCEPTION 'Import parser job identity is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status = 'queued' AND NEW.status = 'parsing'
       AND NEW.attempt = OLD.attempt + 1
       AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL AND NEW.preview_version IS NULL
       AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL
       AND NEW.completed_at IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'parsing' AND NEW.status = 'parsing'
       AND NEW.attempt = OLD.attempt + 1
       AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL AND NEW.preview_version IS NULL
       AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL
       AND NEW.completed_at IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'parsing' AND NEW.status IN ('ready_to_commit', 'failed')
       AND NEW.attempt = OLD.attempt
       AND NEW.lease_expires_at IS NULL AND NEW.completed_at IS NOT NULL
       AND ((NEW.status = 'ready_to_commit' AND NEW.preview_version = 1)
            OR (NEW.status = 'failed' AND NEW.preview_version IS NULL)) THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'ready_to_commit' AND NEW.status = 'ready_to_commit'
       AND NEW.attempt = OLD.attempt
       AND NEW.preview_version = OLD.preview_version + 1
       AND NEW.preview_data IS NOT NULL AND NEW.preview_etag IS NOT NULL
       AND NEW.preview_data IS DISTINCT FROM OLD.preview_data
       AND NEW.preview_etag IS DISTINCT FROM OLD.preview_etag
       AND NEW.warnings IS NOT DISTINCT FROM OLD.warnings
       AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NULL
       AND NEW.completed_at IS NOT DISTINCT FROM OLD.completed_at THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'ready_to_commit' AND NEW.status = 'committed'
       AND NEW.attempt = OLD.attempt
       AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL AND NEW.preview_version IS NULL
       AND NEW.warnings IS NOT DISTINCT FROM OLD.warnings
       AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NULL
       AND NEW.completed_at IS NOT DISTINCT FROM OLD.completed_at THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Unsupported import parser job transition'
        USING ERRCODE = '55000';
END;
$$;
--rollback CREATE OR REPLACE FUNCTION guard_import_parse_job_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.import_id IS DISTINCT FROM OLD.import_id OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.parser_version IS DISTINCT FROM OLD.parser_version THEN RAISE EXCEPTION 'Import parser job identity is immutable' USING ERRCODE = '55000'; END IF; IF OLD.status = 'queued' AND NEW.status = 'parsing' AND NEW.attempt = OLD.attempt + 1 AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL AND NEW.preview_version IS NULL AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL AND NEW.completed_at IS NULL THEN RETURN NEW; END IF; IF OLD.status = 'parsing' AND NEW.status = 'parsing' AND NEW.attempt = OLD.attempt + 1 AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL AND NEW.preview_version IS NULL AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL AND NEW.completed_at IS NULL THEN RETURN NEW; END IF; IF OLD.status = 'parsing' AND NEW.status IN ('ready_to_commit', 'failed') AND NEW.attempt = OLD.attempt AND NEW.lease_expires_at IS NULL AND NEW.completed_at IS NOT NULL AND ((NEW.status = 'ready_to_commit' AND NEW.preview_version = 1) OR (NEW.status = 'failed' AND NEW.preview_version IS NULL)) THEN RETURN NEW; END IF; IF OLD.status = 'ready_to_commit' AND NEW.status = 'ready_to_commit' AND NEW.attempt = OLD.attempt AND NEW.preview_version = OLD.preview_version + 1 AND NEW.preview_data IS NOT NULL AND NEW.preview_etag IS NOT NULL AND NEW.preview_data IS DISTINCT FROM OLD.preview_data AND NEW.preview_etag IS DISTINCT FROM OLD.preview_etag AND NEW.warnings IS NOT DISTINCT FROM OLD.warnings AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NULL AND NEW.completed_at IS NOT DISTINCT FROM OLD.completed_at THEN RETURN NEW; END IF; RAISE EXCEPTION 'Unsupported import parser job transition' USING ERRCODE = '55000'; END; $$;

--changeset novelweb:imports-069-commit-idempotency
CREATE TABLE import_commits (
    import_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    work_id UUID NOT NULL UNIQUE REFERENCES works(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_import_commits_actor_job
        FOREIGN KEY (user_id, import_id) REFERENCES import_jobs(user_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_import_commits_actor_key UNIQUE (user_id, idempotency_key)
);
--rollback DROP TABLE import_commits;
