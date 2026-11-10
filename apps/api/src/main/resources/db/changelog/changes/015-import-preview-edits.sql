--liquibase formatted sql

--changeset novelweb:imports-064-preview-version
ALTER TABLE import_parse_jobs ADD COLUMN preview_version INTEGER;

UPDATE import_parse_jobs
SET preview_version = 1
WHERE status = 'ready_to_commit';

ALTER TABLE import_parse_jobs
    ADD CONSTRAINT ck_import_parse_preview_version_state
    CHECK ((status = 'ready_to_commit' AND preview_version >= 1)
        OR (status <> 'ready_to_commit' AND preview_version IS NULL));
--rollback ALTER TABLE import_parse_jobs DROP CONSTRAINT ck_import_parse_preview_version_state;
--rollback ALTER TABLE import_parse_jobs DROP COLUMN preview_version;

--changeset novelweb:imports-065-preview-edit-transition-guard splitStatements:false rollbackSplitStatements:false
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

    RAISE EXCEPTION 'Unsupported import parser job transition'
        USING ERRCODE = '55000';
END;
$$;
--rollback CREATE OR REPLACE FUNCTION guard_import_parse_job_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.import_id IS DISTINCT FROM OLD.import_id OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.parser_version IS DISTINCT FROM OLD.parser_version THEN RAISE EXCEPTION 'Import parser job identity is immutable' USING ERRCODE = '55000'; END IF; IF OLD.status = 'queued' AND NEW.status = 'parsing' AND NEW.attempt = OLD.attempt + 1 AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL AND NEW.completed_at IS NULL THEN RETURN NEW; END IF; IF OLD.status = 'parsing' AND NEW.status = 'parsing' AND NEW.attempt = OLD.attempt + 1 AND NEW.preview_data IS NULL AND NEW.preview_etag IS NULL AND NEW.failure_code IS NULL AND NEW.lease_expires_at IS NOT NULL AND NEW.completed_at IS NULL THEN RETURN NEW; END IF; IF OLD.status = 'parsing' AND NEW.status IN ('ready_to_commit', 'failed') AND NEW.attempt = OLD.attempt AND NEW.lease_expires_at IS NULL AND NEW.completed_at IS NOT NULL THEN RETURN NEW; END IF; RAISE EXCEPTION 'Unsupported import parser job transition' USING ERRCODE = '55000'; END; $$;
