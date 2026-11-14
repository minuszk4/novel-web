--liquibase formatted sql

--changeset novelweb:authoring-002-chapter-creation-idempotency
CREATE TABLE authoring_chapter_creations (
    actor_user_id UUID NOT NULL,
    work_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    chapter_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_user_id, work_id, idempotency_key),
    CONSTRAINT fk_authoring_chapter_creation_owner_work
        FOREIGN KEY (actor_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_authoring_chapter_creation_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT
);
--rollback DROP TABLE authoring_chapter_creations;
