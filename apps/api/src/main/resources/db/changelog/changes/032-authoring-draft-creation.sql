--liquibase formatted sql

--changeset novelweb:authoring-001-draft-creation-idempotency
CREATE TABLE authoring_manuscript_creations (
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    work_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_user_id, idempotency_key),
    CONSTRAINT fk_authoring_manuscript_creation_owner_work
        FOREIGN KEY (actor_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT
);
--rollback DROP TABLE authoring_manuscript_creations;
