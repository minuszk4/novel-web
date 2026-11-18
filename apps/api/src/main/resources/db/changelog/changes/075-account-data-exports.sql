--liquibase formatted sql

--changeset novelweb:privacy-001-account-data-exports
CREATE TABLE account_data_exports (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key_hash BYTEA NOT NULL CHECK (octet_length(idempotency_key_hash) = 32),
    status VARCHAR(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'ready', 'failed', 'expired')),
    storage_key UUID UNIQUE,
    artifact_sha256 BYTEA CHECK (artifact_sha256 IS NULL OR octet_length(artifact_sha256) = 32),
    artifact_bytes BIGINT CHECK (artifact_bytes IS NULL OR artifact_bytes > 0),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    ready_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    failure_code VARCHAR(40)
        CHECK (failure_code IS NULL OR failure_code IN (
            'GENERATION_FAILED', 'SIZE_LIMIT_EXCEEDED', 'STORAGE_UNAVAILABLE'
        )),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT uq_account_data_export_actor_idempotency
        UNIQUE (user_id, idempotency_key_hash),
    CHECK (
        (status = 'queued' AND storage_key IS NULL AND artifact_sha256 IS NULL
            AND artifact_bytes IS NULL AND ready_at IS NULL AND expires_at IS NULL
            AND failure_code IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR (status = 'processing' AND storage_key IS NULL AND artifact_sha256 IS NULL
            AND artifact_bytes IS NULL AND ready_at IS NULL AND expires_at IS NULL
            AND failure_code IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status = 'ready' AND storage_key IS NOT NULL AND artifact_sha256 IS NOT NULL
            AND artifact_bytes IS NOT NULL AND ready_at IS NOT NULL AND expires_at > ready_at
            AND failure_code IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR (status = 'failed' AND storage_key IS NULL AND artifact_sha256 IS NULL
            AND artifact_bytes IS NULL AND ready_at IS NULL AND expires_at IS NULL
            AND failure_code IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR (status = 'expired' AND storage_key IS NULL AND artifact_sha256 IS NULL
            AND artifact_bytes IS NULL AND ready_at IS NOT NULL AND expires_at IS NOT NULL
            AND failure_code IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
);
CREATE INDEX ix_account_data_exports_worker_queue
    ON account_data_exports (requested_at, id)
    WHERE status = 'queued';
CREATE INDEX ix_account_data_exports_owner_recent
    ON account_data_exports (user_id, requested_at DESC, id DESC);
CREATE INDEX ix_account_data_exports_expiry
    ON account_data_exports (expires_at, id)
    WHERE status = 'ready';
--rollback DROP TABLE account_data_exports;
