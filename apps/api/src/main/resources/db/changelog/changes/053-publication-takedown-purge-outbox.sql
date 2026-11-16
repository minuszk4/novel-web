--liquibase formatted sql

--changeset novelweb:publication-053-takedown-purge-outbox splitStatements:false rollbackSplitStatements:false
ALTER TABLE publication_work_takedown_events
    ADD COLUMN external_cache_purge_status VARCHAR(24) NOT NULL DEFAULT 'NOT_CONFIGURED'
        CHECK (external_cache_purge_status IN ('NOT_CONFIGURED', 'REQUESTED'));

CREATE TABLE publication_takedown_purge_requests (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES publication_work_takedown_events(id) ON DELETE CASCADE,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    target_code VARCHAR(24) NOT NULL
        CHECK (target_code IN ('CDN', 'SEARCH_INDEX', 'FEED_CACHE', 'SIGNED_LINKS')),
    status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'IN_PROGRESS', 'ACKNOWLEDGED', 'FAILED')),
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 8),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_safe_failure_code VARCHAR(40)
        CHECK (last_safe_failure_code IS NULL OR last_safe_failure_code IN (
            'REMOTE_REJECTED', 'TRANSPORT_FAILED', 'DELIVERY_INTERRUPTED',
            'LEASE_EXPIRED', 'TARGET_NOT_CONFIGURED'
        )),
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT uq_publication_takedown_purge_target UNIQUE (event_id, target_code),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((status = 'IN_PROGRESS') = (lease_token IS NOT NULL)),
    CHECK ((status = 'ACKNOWLEDGED') = (acknowledged_at IS NOT NULL)),
    CHECK (status <> 'ACKNOWLEDGED' OR last_safe_failure_code IS NULL)
);

CREATE INDEX ix_publication_takedown_purge_due
    ON publication_takedown_purge_requests (next_attempt_at, requested_at, id)
    WHERE status = 'REQUESTED';
CREATE INDEX ix_publication_takedown_purge_expired_lease
    ON publication_takedown_purge_requests (lease_until, id)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX ix_publication_takedown_purge_work
    ON publication_takedown_purge_requests (work_id, requested_at DESC, target_code);

CREATE TABLE publication_takedown_purge_attempts (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES publication_takedown_purge_requests(id) ON DELETE CASCADE,
    attempt_number SMALLINT NOT NULL CHECK (attempt_number BETWEEN 1 AND 8),
    outcome VARCHAR(24) NOT NULL
        CHECK (outcome IN ('ACKNOWLEDGED', 'REMOTE_REJECTED', 'TRANSPORT_FAILED',
                           'INTERRUPTED', 'LEASE_EXPIRED')),
    safe_failure_code VARCHAR(40)
        CHECK (safe_failure_code IS NULL OR safe_failure_code IN (
            'REMOTE_REJECTED', 'TRANSPORT_FAILED', 'DELIVERY_INTERRUPTED',
            'LEASE_EXPIRED', 'TARGET_NOT_CONFIGURED'
        )),
    finished_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_publication_takedown_purge_attempt UNIQUE (request_id, attempt_number),
    CHECK ((outcome = 'ACKNOWLEDGED') = (safe_failure_code IS NULL))
);

CREATE INDEX ix_publication_takedown_purge_attempt_history
    ON publication_takedown_purge_attempts (request_id, attempt_number DESC);

CREATE TRIGGER trg_publication_takedown_purge_attempt_immutable
    BEFORE UPDATE OR DELETE ON publication_takedown_purge_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_publication_scope_access_audit_mutation();
CREATE TRIGGER trg_publication_takedown_purge_attempt_truncate_immutable
    BEFORE TRUNCATE ON publication_takedown_purge_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_scope_access_audit_truncate();

--rollback DROP TRIGGER trg_publication_takedown_purge_attempt_truncate_immutable ON publication_takedown_purge_attempts;
--rollback DROP TRIGGER trg_publication_takedown_purge_attempt_immutable ON publication_takedown_purge_attempts;
--rollback DROP INDEX ix_publication_takedown_purge_attempt_history;
--rollback DROP TABLE publication_takedown_purge_attempts;
--rollback DROP INDEX ix_publication_takedown_purge_work;
--rollback DROP INDEX ix_publication_takedown_purge_expired_lease;
--rollback DROP INDEX ix_publication_takedown_purge_due;
--rollback DROP TABLE publication_takedown_purge_requests;
--rollback ALTER TABLE publication_work_takedown_events DROP COLUMN external_cache_purge_status;
