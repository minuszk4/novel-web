--liquibase formatted sql

--changeset novelweb:publication-079-release-schedules splitStatements:false rollbackSplitStatements:false
CREATE TABLE publication_release_schedules (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    request_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    readiness_etag VARCHAR(96) NOT NULL
        CHECK (readiness_etag ~ '^"publication-snapshot-[a-f0-9]{64}"$'),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    state VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (state IN ('SCHEDULED', 'PROCESSING', 'PUBLISHED', 'CANCELLED', 'BLOCKED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    publication_event_id UUID REFERENCES publication_release_events(id) ON DELETE RESTRICT,
    failure_code VARCHAR(48)
        CHECK (failure_code IS NULL OR failure_code IN (
            'PUBLICATION_SNAPSHOT_STALE', 'PUBLICATION_APPROVAL_CHANGED',
            'PUBLICATION_APPROVAL_REQUIRED', 'PUBLICATION_POLICY_CHANGED',
            'PUBLICATION_STATE_CHANGED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_publication_schedule_request
        FOREIGN KEY (request_id, work_id, owner_user_id)
        REFERENCES publication_requests(id, work_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_publication_schedule_owner_work
        FOREIGN KEY (owner_user_id, work_id)
        REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_publication_schedule_idempotency
        UNIQUE (owner_user_id, work_id, idempotency_key_sha256),
    CONSTRAINT uq_publication_schedule_id_work UNIQUE (id, work_id),
    CHECK (scheduled_at > created_at),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((state = 'PROCESSING') = (lease_token IS NOT NULL)),
    CHECK ((state = 'PUBLISHED') = (publication_event_id IS NOT NULL)),
    CHECK ((state = 'BLOCKED') = (failure_code IS NOT NULL))
);

CREATE UNIQUE INDEX uq_publication_schedule_active_request
    ON publication_release_schedules (request_id)
    WHERE state IN ('SCHEDULED', 'PROCESSING');
CREATE INDEX ix_publication_schedule_due
    ON publication_release_schedules (scheduled_at, id)
    WHERE state = 'SCHEDULED';
CREATE INDEX ix_publication_schedule_expired_lease
    ON publication_release_schedules (lease_until, id)
    WHERE state = 'PROCESSING';
CREATE INDEX ix_publication_schedule_owner_history
    ON publication_release_schedules (owner_user_id, work_id, created_at DESC, id DESC);

CREATE TABLE publication_release_schedule_events (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES publication_release_schedules(id) ON DELETE CASCADE,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action_code VARCHAR(16) NOT NULL CHECK (action_code IN (
        'SCHEDULED', 'CANCELLED', 'PUBLISHED', 'BLOCKED')),
    safe_failure_code VARCHAR(48)
        CHECK (safe_failure_code IS NULL OR safe_failure_code IN (
            'PUBLICATION_SNAPSHOT_STALE', 'PUBLICATION_APPROVAL_CHANGED',
            'PUBLICATION_APPROVAL_REQUIRED', 'PUBLICATION_POLICY_CHANGED',
            'PUBLICATION_STATE_CHANGED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_publication_schedule_event_work
        FOREIGN KEY (schedule_id, work_id)
        REFERENCES publication_release_schedules(id, work_id) ON DELETE CASCADE,
    CHECK ((action_code = 'BLOCKED') = (safe_failure_code IS NOT NULL))
);
CREATE INDEX ix_publication_schedule_event_history
    ON publication_release_schedule_events (schedule_id, occurred_at, id);

CREATE FUNCTION validate_publication_release_schedule()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    owner_is_active BOOLEAN;
    work_visibility VARCHAR(16);
    request_state VARCHAR(32);
    request_policy_version VARCHAR(64);
    request_policy_sha256 BYTEA;
BEGIN
    IF NEW.state <> 'SCHEDULED' OR NEW.attempt_count <> 0 OR NEW.lease_token IS NOT NULL
       OR NEW.lease_until IS NOT NULL OR NEW.publication_event_id IS NOT NULL
       OR NEW.failure_code IS NOT NULL THEN
        RAISE EXCEPTION 'A new publication schedule must start pending'
            USING ERRCODE = '23514';
    END IF;

    SELECT owner.status = 'active', work.visibility
      INTO owner_is_active, work_visibility
      FROM works work
      JOIN users owner ON owner.id = work.owner_user_id
      JOIN libraries library
        ON library.id = work.library_id
       AND library.owner_user_id = work.owner_user_id
       AND library.deleted_at IS NULL
       AND library.workspace_id IS NULL
      JOIN authoring_manuscript_creations creation
        ON creation.work_id = work.id
       AND creation.actor_user_id = work.owner_user_id
     WHERE work.id = NEW.work_id
       AND work.owner_user_id = NEW.owner_user_id
       AND work.source_kind = 'authored'
       AND work.status = 'active'
       AND work.deleted_at IS NULL
     FOR UPDATE OF work, owner, library, creation;
    IF NOT FOUND OR owner_is_active IS DISTINCT FROM TRUE
       OR work_visibility IS DISTINCT FROM 'private' THEN
        RAISE EXCEPTION 'An active owner-authored private manuscript is required for scheduling'
            USING ERRCODE = '23514';
    END IF;

    SELECT state.current_state, request.policy_version, request.policy_sha256
      INTO request_state, request_policy_version, request_policy_sha256
      FROM publication_requests request
      JOIN publication_request_current_state state ON state.request_id = request.id
     WHERE request.id = NEW.request_id
       AND request.work_id = NEW.work_id
       AND request.owner_user_id = NEW.owner_user_id
     FOR UPDATE OF request;
    IF NOT FOUND OR request_state IS DISTINCT FROM 'APPROVED_UNPUBLISHED' THEN
        RAISE EXCEPTION 'A current approved-unpublished request is required for scheduling'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM publication_requests newer
         WHERE newer.work_id = NEW.work_id
           AND (newer.request_version > (
                    SELECT request_version FROM publication_requests WHERE id = NEW.request_id
                ) OR (newer.request_version = (
                    SELECT request_version FROM publication_requests WHERE id = NEW.request_id
                ) AND newer.id > NEW.request_id))
    ) THEN
        RAISE EXCEPTION 'Only the latest approved request can be scheduled'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.scheduled_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'A publication schedule must be in the future'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM publication_release_events WHERE request_id = NEW.request_id) THEN
        RAISE EXCEPTION 'An already released request cannot be scheduled'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_publication_release_schedule_validate
    BEFORE INSERT ON publication_release_schedules
    FOR EACH ROW EXECUTE FUNCTION validate_publication_release_schedule();

CREATE FUNCTION guard_publication_release_schedule_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.work_id IS DISTINCT FROM OLD.work_id
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.scheduled_at IS DISTINCT FROM OLD.scheduled_at
       OR NEW.readiness_etag IS DISTINCT FROM OLD.readiness_etag
       OR NEW.idempotency_key_sha256 IS DISTINCT FROM OLD.idempotency_key_sha256
       OR NEW.request_sha256 IS DISTINCT FROM OLD.request_sha256
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'Publication schedule intent is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NOT (
        (OLD.state = 'SCHEDULED' AND NEW.state IN ('SCHEDULED', 'PROCESSING', 'CANCELLED', 'BLOCKED', 'PUBLISHED'))
        OR (OLD.state = 'PROCESSING' AND NEW.state IN ('PROCESSING', 'SCHEDULED', 'CANCELLED', 'BLOCKED', 'PUBLISHED'))
        OR (OLD.state IN ('PUBLISHED', 'CANCELLED', 'BLOCKED') AND NEW.state = OLD.state)
    ) THEN
        RAISE EXCEPTION 'Invalid publication schedule state transition'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'PROCESSING' AND (NEW.lease_token IS NULL OR NEW.lease_until <= clock_timestamp()) THEN
        RAISE EXCEPTION 'A processing schedule requires a live lease'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> 'PROCESSING' AND (NEW.lease_token IS NOT NULL OR NEW.lease_until IS NOT NULL) THEN
        RAISE EXCEPTION 'A terminal or pending schedule cannot retain a lease'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'PUBLISHED' AND NEW.publication_event_id IS NULL THEN
        RAISE EXCEPTION 'Published schedule requires a release receipt'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'BLOCKED' AND NEW.failure_code IS NULL THEN
        RAISE EXCEPTION 'Blocked schedule requires a safe reason code'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state NOT IN ('BLOCKED', 'PUBLISHED') AND NEW.failure_code IS NOT NULL THEN
        RAISE EXCEPTION 'Only blocked schedules may retain a failure code'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_publication_release_schedule_guard
    BEFORE UPDATE ON publication_release_schedules
    FOR EACH ROW EXECUTE FUNCTION guard_publication_release_schedule_update();

CREATE FUNCTION guard_publication_release_schedule_event()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    schedule_owner UUID;
    schedule_state VARCHAR(16);
    schedule_work UUID;
BEGIN
    SELECT owner_user_id, state, work_id
      INTO schedule_owner, schedule_state, schedule_work
      FROM publication_release_schedules WHERE id = NEW.schedule_id;
    IF NOT FOUND OR schedule_work IS DISTINCT FROM NEW.work_id THEN
        RAISE EXCEPTION 'Publication schedule event must match its schedule'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.action_code IN ('SCHEDULED', 'CANCELLED')
       AND NEW.actor_user_id IS DISTINCT FROM schedule_owner THEN
        RAISE EXCEPTION 'Owner action must be attributed to the schedule owner'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.action_code = 'SCHEDULED' AND schedule_state IS DISTINCT FROM 'SCHEDULED' THEN
        RAISE EXCEPTION 'Schedule audit event requires pending state'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.action_code = 'CANCELLED' AND schedule_state IS DISTINCT FROM 'CANCELLED' THEN
        RAISE EXCEPTION 'Cancel audit event requires cancelled state'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.action_code = 'PUBLISHED' AND schedule_state IS DISTINCT FROM 'PUBLISHED' THEN
        RAISE EXCEPTION 'Publish audit event requires published state'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.action_code = 'BLOCKED' AND schedule_state IS DISTINCT FROM 'BLOCKED' THEN
        RAISE EXCEPTION 'Blocked audit event requires blocked state'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_publication_release_schedule_event_validate
    BEFORE INSERT ON publication_release_schedule_events
    FOR EACH ROW EXECUTE FUNCTION guard_publication_release_schedule_event();

CREATE FUNCTION reject_publication_release_schedule_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Publication release schedule history is append-only'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_publication_release_schedule_event_immutable
    BEFORE UPDATE OR DELETE ON publication_release_schedule_events
    FOR EACH ROW EXECUTE FUNCTION reject_publication_release_schedule_event_mutation();
CREATE TRIGGER trg_publication_release_schedule_event_truncate_immutable
    BEFORE TRUNCATE ON publication_release_schedule_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_release_schedule_event_mutation();

--rollback DROP TRIGGER trg_publication_release_schedule_event_truncate_immutable ON publication_release_schedule_events;
--rollback DROP TRIGGER trg_publication_release_schedule_event_immutable ON publication_release_schedule_events;
--rollback DROP TRIGGER trg_publication_release_schedule_event_validate ON publication_release_schedule_events;
--rollback DROP FUNCTION reject_publication_release_schedule_event_mutation();
--rollback DROP FUNCTION guard_publication_release_schedule_event();
--rollback DROP TRIGGER trg_publication_release_schedule_guard ON publication_release_schedules;
--rollback DROP FUNCTION guard_publication_release_schedule_update();
--rollback DROP TRIGGER trg_publication_release_schedule_validate ON publication_release_schedules;
--rollback DROP FUNCTION validate_publication_release_schedule();
--rollback DROP TABLE publication_release_schedule_events;
--rollback DROP TABLE publication_release_schedules;
