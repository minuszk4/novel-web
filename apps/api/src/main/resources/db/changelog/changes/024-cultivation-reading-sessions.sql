--liquibase formatted sql

--changeset novelweb:cultivation-009-reading-sessions
CREATE TABLE cultivation_reading_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    catalog_version VARCHAR(32) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    start_idempotency_key VARCHAR(128) NOT NULL
        CHECK (start_idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    start_request_sha256 BYTEA NOT NULL CHECK (octet_length(start_request_sha256) = 32),
    state VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (state IN ('active', 'closed', 'expired', 'revoked')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_client_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    total_active_seconds BIGINT NOT NULL DEFAULT 0 CHECK (total_active_seconds >= 0),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_cultivation_reading_session_chapter
        FOREIGN KEY (work_id, chapter_id)
        REFERENCES chapters (work_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_cultivation_reading_session_catalog_policy
        FOREIGN KEY (catalog_version, policy_version)
        REFERENCES cultivation_rank_catalogs (version, policy_version) ON DELETE RESTRICT,
    CONSTRAINT uq_cultivation_reading_session_start_key
        UNIQUE (user_id, start_idempotency_key),
    CHECK (last_heartbeat_at >= started_at),
    CHECK (
        (state = 'active' AND closed_at IS NULL)
        OR (state <> 'active' AND closed_at IS NOT NULL AND closed_at >= started_at)
    )
);
CREATE UNIQUE INDEX uq_cultivation_reading_session_one_active_per_user
    ON cultivation_reading_sessions (user_id)
    WHERE state = 'active';
CREATE INDEX ix_cultivation_reading_session_cleanup
    ON cultivation_reading_sessions (last_heartbeat_at);
--rollback DROP TABLE cultivation_reading_sessions;

--changeset novelweb:cultivation-010-reading-session-heartbeats
CREATE TABLE cultivation_reading_session_events (
    session_id UUID NOT NULL
        REFERENCES cultivation_reading_sessions(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    client_active BOOLEAN NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    credited_seconds SMALLINT NOT NULL CHECK (credited_seconds BETWEEN 0 AND 60),
    total_active_seconds BIGINT NOT NULL CHECK (total_active_seconds >= 0),
    result_state VARCHAR(16) NOT NULL
        CHECK (result_state IN ('active', 'expired', 'revoked')),
    PRIMARY KEY (session_id, idempotency_key),
    UNIQUE (session_id, sequence)
);
CREATE INDEX ix_cultivation_reading_session_event_received
    ON cultivation_reading_session_events (session_id, received_at DESC);
--rollback DROP TABLE cultivation_reading_session_events;

--changeset novelweb:cultivation-011-reading-session-event-immutability splitStatements:false
CREATE FUNCTION guard_cultivation_reading_session_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Cultivation reading session events are append-only'
        USING ERRCODE = '55000';
END;
$$;
--rollback DROP FUNCTION guard_cultivation_reading_session_event_mutation();

--changeset novelweb:cultivation-012-reading-session-event-immutability-trigger
CREATE TRIGGER trg_cultivation_reading_session_event_immutable
    BEFORE UPDATE OR DELETE ON cultivation_reading_session_events
    FOR EACH ROW EXECUTE FUNCTION guard_cultivation_reading_session_event_mutation();
--rollback DROP TRIGGER trg_cultivation_reading_session_event_immutable ON cultivation_reading_session_events;
