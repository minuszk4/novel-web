--liquibase formatted sql

--changeset novelweb:imports-061-parser-jobs
CREATE TABLE import_parse_jobs (
    import_id UUID PRIMARY KEY REFERENCES import_jobs(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('queued', 'parsing', 'ready_to_commit', 'failed')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    parser_version VARCHAR(32) NOT NULL CHECK (length(btrim(parser_version)) BETWEEN 1 AND 32),
    preview_data JSONB,
    preview_etag BYTEA CHECK (preview_etag IS NULL OR octet_length(preview_etag) = 32),
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(warnings) = 'array' AND jsonb_array_length(warnings) <= 100),
    failure_code VARCHAR(96),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CHECK (preview_data IS NULL OR
           (jsonb_typeof(preview_data) = 'object' AND octet_length(preview_data::text) <= 1048576)),
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
    )
);

CREATE INDEX ix_import_parse_jobs_queue
    ON import_parse_jobs (created_at, import_id)
    WHERE status = 'queued';

CREATE INDEX ix_import_parse_jobs_expired_lease
    ON import_parse_jobs (lease_expires_at, import_id)
    WHERE status = 'parsing';
--rollback DROP TABLE import_parse_jobs;

--changeset novelweb:imports-062-parser-job-transition-guard splitStatements:false
CREATE FUNCTION guard_import_parse_job_update()
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
       AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL
       AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL
       AND NEW.completed_at IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'parsing' AND NEW.status = 'parsing'
       AND NEW.attempt = OLD.attempt + 1
       AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL
       AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL
       AND NEW.completed_at IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'parsing' AND NEW.status IN ('ready_to_commit', 'failed')
       AND NEW.attempt = OLD.attempt
       AND NEW.lease_expires_at IS NULL AND NEW.completed_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Unsupported import parser job transition'
        USING ERRCODE = '55000';
END;
$$;
--rollback DROP FUNCTION guard_import_parse_job_update();

--changeset novelweb:imports-063-parser-job-transition-trigger
CREATE TRIGGER trg_import_parse_job_update_guard
    BEFORE UPDATE ON import_parse_jobs
    FOR EACH ROW EXECUTE FUNCTION guard_import_parse_job_update();
--rollback DROP TRIGGER trg_import_parse_job_update_guard ON import_parse_jobs;
