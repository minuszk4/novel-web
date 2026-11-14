--liquibase formatted sql

--changeset novelweb:translation-001-job-model splitStatements:true
ALTER TABLE segments
    ADD CONSTRAINT uq_segments_revision_id_id UNIQUE (chapter_revision_id, id);

CREATE TABLE translation_jobs (
    id UUID PRIMARY KEY,
    requested_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_language VARCHAR(35) NOT NULL
        CHECK (source_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    target_language VARCHAR(35) NOT NULL
        CHECK (target_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    provider_id VARCHAR(64) NOT NULL CHECK (provider_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    snapshot_schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (snapshot_schema_version > 0),
    profile_snapshot JSONB NOT NULL
        CHECK (jsonb_typeof(profile_snapshot) = 'object'
            AND octet_length(profile_snapshot::text) <= 65536),
    glossary_snapshot JSONB NOT NULL DEFAULT '[]'::JSONB
        CHECK (jsonb_typeof(glossary_snapshot) = 'array'
            AND octet_length(glossary_snapshot::text) <= 1048576),
    source_sha256 BYTEA NOT NULL CHECK (octet_length(source_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    status VARCHAR(24) NOT NULL DEFAULT 'preparing'
        CHECK (status IN ('queued', 'preparing', 'translating', 'validating', 'retry_wait',
            'review_required', 'completed', 'failed', 'cancel_requested', 'cancelled')),
    total_units INTEGER NOT NULL DEFAULT 0 CHECK (total_units >= 0),
    completed_units INTEGER NOT NULL DEFAULT 0 CHECK (completed_units >= 0),
    failed_units INTEGER NOT NULL DEFAULT 0 CHECK (failed_units >= 0),
    estimated_amount NUMERIC(20, 8) CHECK (estimated_amount IS NULL OR estimated_amount >= 0),
    estimated_currency VARCHAR(3)
        CHECK (estimated_currency IS NULL OR estimated_currency ~ '^[A-Z]{3}$'),
    estimated_billable_units BIGINT
        CHECK (estimated_billable_units IS NULL OR estimated_billable_units >= 0),
    estimated_billing_unit VARCHAR(24)
        CHECK (estimated_billing_unit IS NULL OR estimated_billing_unit IN
            ('CODE_POINT', 'TOKEN', 'REQUEST')),
    pricing_version VARCHAR(64)
        CHECK (pricing_version IS NULL OR pricing_version ~ '^[A-Za-z0-9._-]{1,64}$'),
    final_billed_units BIGINT CHECK (final_billed_units IS NULL OR final_billed_units >= 0),
    failure_code VARCHAR(40)
        CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    CONSTRAINT uq_translation_job_request_idempotency
        UNIQUE (requested_by_user_id, chapter_id, idempotency_key),
    CONSTRAINT uq_translation_job_source_snapshot
        UNIQUE (id, source_revision_id),
    CONSTRAINT uq_translation_job_candidate_context
        UNIQUE (id, work_id, chapter_id, target_language, source_revision_id),
    CONSTRAINT fk_translation_job_work
        FOREIGN KEY (work_id) REFERENCES works(id) ON DELETE RESTRICT,
    CONSTRAINT fk_translation_job_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_translation_job_source_revision
        FOREIGN KEY (chapter_id, source_revision_id)
        REFERENCES chapter_revisions(chapter_id, id) ON DELETE RESTRICT,
    CHECK (lower(source_language) <> lower(target_language)),
    CHECK (completed_units + failed_units <= total_units),
    CHECK (status <> 'queued' OR total_units > 0),
    CHECK ((estimated_amount IS NULL AND estimated_currency IS NULL
                AND estimated_billable_units IS NULL AND estimated_billing_unit IS NULL
                AND pricing_version IS NULL)
        OR (estimated_amount IS NOT NULL AND estimated_currency IS NOT NULL
                AND estimated_billable_units IS NOT NULL AND estimated_billing_unit IS NOT NULL
                AND pricing_version IS NOT NULL))
);
CREATE INDEX ix_translation_jobs_requester_created
    ON translation_jobs (requested_by_user_id, created_at DESC, id DESC);
CREATE INDEX ix_translation_jobs_worker_queue
    ON translation_jobs (status, created_at, id)
    WHERE status IN ('queued', 'preparing', 'retry_wait');

CREATE TABLE translation_units (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    unit_no INTEGER NOT NULL CHECK (unit_no > 0),
    idempotency_key UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'succeeded', 'retry_wait', 'failed', 'cancelled')),
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    max_attempts SMALLINT NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 5),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_owner VARCHAR(128)
        CHECK (lease_owner IS NULL OR length(btrim(lease_owner)) BETWEEN 1 AND 128),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    last_failure_code VARCHAR(40)
        CHECK (last_failure_code IS NULL OR last_failure_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_translation_unit_id_job UNIQUE (job_id, id),
    CONSTRAINT uq_translation_unit_context UNIQUE (job_id, id, source_revision_id),
    CONSTRAINT uq_translation_unit_number UNIQUE (job_id, unit_no),
    CONSTRAINT uq_translation_unit_idempotency UNIQUE (job_id, idempotency_key),
    CONSTRAINT fk_translation_unit_job_source
        FOREIGN KEY (job_id, source_revision_id)
        REFERENCES translation_jobs(id, source_revision_id) ON DELETE CASCADE,
    CHECK ((status = 'running'
                AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'running'
                AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK (attempt_count <= max_attempts),
    CHECK (heartbeat_at IS NULL OR (status = 'running' AND heartbeat_at <= lease_until))
);
CREATE INDEX ix_translation_units_claim
    ON translation_units (available_at, created_at, id)
    WHERE status IN ('queued', 'retry_wait');
CREATE INDEX ix_translation_units_expired_lease
    ON translation_units (lease_until, id) WHERE status = 'running';

CREATE TABLE translation_unit_segments (
    job_id UUID NOT NULL,
    unit_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_segment_id UUID NOT NULL,
    segment_ordinal INTEGER NOT NULL CHECK (segment_ordinal > 0),
    source_sha256 BYTEA NOT NULL CHECK (octet_length(source_sha256) = 32),
    PRIMARY KEY (unit_id, segment_ordinal),
    CONSTRAINT uq_translation_job_source_segment
        UNIQUE (job_id, source_revision_id, source_segment_id),
    CONSTRAINT uq_translation_unit_source_segment
        UNIQUE (unit_id, job_id, source_revision_id, source_segment_id),
    CONSTRAINT fk_translation_unit_segment_unit
        FOREIGN KEY (job_id, unit_id, source_revision_id)
        REFERENCES translation_units(job_id, id, source_revision_id) ON DELETE CASCADE,
    CONSTRAINT fk_translation_unit_segment_job
        FOREIGN KEY (job_id, source_revision_id)
        REFERENCES translation_jobs(id, source_revision_id) ON DELETE CASCADE,
    CONSTRAINT fk_translation_unit_segment_source
        FOREIGN KEY (source_revision_id, source_segment_id)
        REFERENCES segments(chapter_revision_id, id) ON DELETE RESTRICT
);

CREATE TABLE translation_attempts (
    id UUID PRIMARY KEY,
    unit_id UUID NOT NULL REFERENCES translation_units(id) ON DELETE CASCADE,
    attempt_no SMALLINT NOT NULL CHECK (attempt_no BETWEEN 1 AND 5),
    provider_id VARCHAR(64) NOT NULL CHECK (provider_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    provider_model VARCHAR(128)
        CHECK (provider_model IS NULL OR length(btrim(provider_model)) BETWEEN 1 AND 128),
    provider_request_id VARCHAR(256)
        CHECK (provider_request_id IS NULL OR length(btrim(provider_request_id)) BETWEEN 1 AND 256),
    outcome VARCHAR(24) NOT NULL
        CHECK (outcome IN ('running', 'succeeded', 'rate_limited', 'temporary', 'timeout',
            'quota_exceeded', 'billing_disabled', 'cost_limit_exceeded', 'invalid_response',
            'input_rejected', 'cancelled')),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    response_sha256 BYTEA CHECK (response_sha256 IS NULL OR octet_length(response_sha256) = 32),
    estimate_amount NUMERIC(20, 8) CHECK (estimate_amount IS NULL OR estimate_amount >= 0),
    estimate_currency VARCHAR(3)
        CHECK (estimate_currency IS NULL OR estimate_currency ~ '^[A-Z]{3}$'),
    estimated_units BIGINT CHECK (estimated_units IS NULL OR estimated_units >= 0),
    billed_units BIGINT CHECK (billed_units IS NULL OR billed_units >= 0),
    estimated_billing_unit VARCHAR(24)
        CHECK (estimated_billing_unit IS NULL
            OR estimated_billing_unit IN ('CODE_POINT', 'TOKEN', 'REQUEST')),
    billed_billing_unit VARCHAR(24)
        CHECK (billed_billing_unit IS NULL
            OR billed_billing_unit IN ('CODE_POINT', 'TOKEN', 'REQUEST')),
    pricing_version VARCHAR(64)
        CHECK (pricing_version IS NULL OR pricing_version ~ '^[A-Za-z0-9._-]{1,64}$'),
    duration_ms BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0),
    failure_code VARCHAR(40)
        CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_translation_attempt_number UNIQUE (unit_id, attempt_no),
    CHECK ((outcome = 'running' AND completed_at IS NULL)
        OR (outcome <> 'running' AND completed_at IS NOT NULL)),
    CHECK ((estimate_amount IS NULL AND estimate_currency IS NULL AND estimated_units IS NULL
                AND estimated_billing_unit IS NULL AND pricing_version IS NULL)
        OR (estimate_amount IS NOT NULL AND estimate_currency IS NOT NULL AND estimated_units IS NOT NULL
                AND estimated_billing_unit IS NOT NULL AND pricing_version IS NOT NULL)),
    CHECK ((billed_units IS NULL AND billed_billing_unit IS NULL)
        OR (billed_units IS NOT NULL AND billed_billing_unit IS NOT NULL))
);
CREATE INDEX ix_translation_attempts_unit_history
    ON translation_attempts (unit_id, attempt_no DESC);

CREATE TABLE translation_candidates (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    target_language VARCHAR(35) NOT NULL
        CHECK (target_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    source_revision_id UUID NOT NULL,
    candidate_no INTEGER NOT NULL CHECK (candidate_no > 0),
    source_sha256 BYTEA NOT NULL CHECK (octet_length(source_sha256) = 32),
    status VARCHAR(24) NOT NULL DEFAULT 'assembling'
        CHECK (status IN ('assembling', 'partial', 'ready_for_review', 'rejected',
            'published', 'superseded', 'stale')),
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reviewed_at TIMESTAMPTZ,
    CONSTRAINT uq_translation_candidate_job_number UNIQUE (job_id, candidate_no),
    CONSTRAINT uq_translation_candidate_context
        UNIQUE (id, job_id, work_id, chapter_id, target_language, source_revision_id),
    CONSTRAINT uq_translation_candidate_version_context
        UNIQUE (id, work_id, chapter_id, target_language, source_revision_id),
    CONSTRAINT uq_translation_candidate_job_source
        UNIQUE (id, job_id, source_revision_id),
    CONSTRAINT fk_translation_candidate_job
        FOREIGN KEY (job_id, work_id, chapter_id, target_language, source_revision_id)
        REFERENCES translation_jobs(id, work_id, chapter_id, target_language, source_revision_id)
        ON DELETE CASCADE,
    CHECK ((status IN ('assembling', 'partial', 'ready_for_review', 'stale')
                AND reviewed_at IS NULL)
        OR (status IN ('rejected', 'published', 'superseded') AND reviewed_at IS NOT NULL))
);
CREATE INDEX ix_translation_candidates_job_created
    ON translation_candidates (job_id, created_at DESC, candidate_no DESC);

CREATE TABLE translation_candidate_segments (
    candidate_id UUID NOT NULL,
    job_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_segment_id UUID NOT NULL,
    translated_text TEXT NOT NULL
        CHECK (length(btrim(translated_text)) > 0 AND octet_length(translated_text) <= 1048576),
    translated_sha256 BYTEA NOT NULL CHECK (octet_length(translated_sha256) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (candidate_id, source_segment_id),
    CONSTRAINT fk_translation_candidate_segment_candidate
        FOREIGN KEY (candidate_id, job_id, source_revision_id)
        REFERENCES translation_candidates(id, job_id, source_revision_id) ON DELETE CASCADE,
    CONSTRAINT fk_translation_candidate_segment_source
        FOREIGN KEY (job_id, source_revision_id, source_segment_id)
        REFERENCES translation_unit_segments(job_id, source_revision_id, source_segment_id)
        ON DELETE RESTRICT
);

CREATE TABLE translation_versions (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    target_language VARCHAR(35) NOT NULL
        CHECK (target_language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    source_revision_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('current', 'superseded', 'withdrawn')),
    rollback_of_version_id UUID REFERENCES translation_versions(id) ON DELETE RESTRICT,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    superseded_at TIMESTAMPTZ,
    CONSTRAINT uq_translation_version_number
        UNIQUE (chapter_id, target_language, version_no),
    CONSTRAINT uq_translation_version_candidate UNIQUE (candidate_id),
    CONSTRAINT fk_translation_version_candidate
        FOREIGN KEY (candidate_id, work_id, chapter_id, target_language, source_revision_id)
        REFERENCES translation_candidates(id, work_id, chapter_id, target_language, source_revision_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_translation_version_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_translation_version_source_revision
        FOREIGN KEY (chapter_id, source_revision_id)
        REFERENCES chapter_revisions(chapter_id, id) ON DELETE RESTRICT,
    CHECK ((status = 'current' AND superseded_at IS NULL)
        OR (status <> 'current' AND superseded_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_translation_version_current
    ON translation_versions (chapter_id, target_language) WHERE status = 'current';
CREATE INDEX ix_translation_versions_chapter_history
    ON translation_versions (chapter_id, target_language, version_no DESC);

CREATE TABLE translation_outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES translation_jobs(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL
        CHECK (event_type IN ('translation.job.requested', 'translation.job.cancel_requested',
            'translation.job.retry_requested', 'translation.unit.completed',
            'translation.candidate.ready')),
    schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    deduplication_key VARCHAR(128) NOT NULL
        CHECK (deduplication_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    correlation_id UUID NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 16384),
    status VARCHAR(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'publishing', 'published', 'dead')),
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 20),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_owner VARCHAR(128),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    last_failure_code VARCHAR(40)
        CHECK (last_failure_code IS NULL OR last_failure_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT uq_translation_outbox_deduplication UNIQUE (aggregate_id, deduplication_key),
    CHECK ((status = 'publishing'
                AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'publishing'
                AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'published' AND published_at IS NOT NULL)
        OR (status <> 'published' AND published_at IS NULL))
);
CREATE INDEX ix_translation_outbox_claim
    ON translation_outbox_events (available_at, occurred_at, event_id)
    WHERE status = 'queued';
CREATE INDEX ix_translation_outbox_expired_lease
    ON translation_outbox_events (lease_until, event_id) WHERE status = 'publishing';

--rollback DROP TABLE translation_outbox_events;
--rollback DROP TABLE translation_versions;
--rollback DROP TABLE translation_candidate_segments;
--rollback DROP TABLE translation_candidates;
--rollback DROP TABLE translation_attempts;
--rollback DROP TABLE translation_unit_segments;
--rollback DROP TABLE translation_units;
--rollback DROP TABLE translation_jobs;
--rollback ALTER TABLE segments DROP CONSTRAINT uq_segments_revision_id_id;
