--liquibase formatted sql

--changeset novelweb:authoring-003-draft-revision-idempotency
CREATE TABLE authoring_chapter_revision_writes (
    actor_user_id UUID NOT NULL,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    revision_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_user_id, chapter_id, idempotency_key),
    CONSTRAINT fk_authoring_revision_write_owner_work
        FOREIGN KEY (actor_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_authoring_revision_write_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_authoring_revision_write_chapter_revision
        FOREIGN KEY (chapter_id, revision_id)
        REFERENCES chapter_revisions(chapter_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_authoring_chapter_revision_writes_recent
    ON authoring_chapter_revision_writes (chapter_id, created_at DESC);
--rollback DROP INDEX ix_authoring_chapter_revision_writes_recent;
--rollback DROP TABLE authoring_chapter_revision_writes;
