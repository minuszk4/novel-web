--liquibase formatted sql

--changeset novelweb:reader-003-bookmarks
CREATE TABLE reader_bookmarks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    chapter_id UUID NOT NULL,
    segment_anchor VARCHAR(160)
        CHECK (segment_anchor IS NULL OR segment_anchor ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'),
    label VARCHAR(160) NOT NULL CHECK (char_length(btrim(label)) BETWEEN 1 AND 160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_reader_bookmark_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_reader_bookmark_position
    ON reader_bookmarks (user_id, work_id, chapter_id, (COALESCE(segment_anchor, '')));
CREATE INDEX ix_reader_bookmark_user_work_created
    ON reader_bookmarks (user_id, work_id, created_at DESC, id DESC);

CREATE TABLE reader_bookmark_idempotency (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (char_length(idempotency_key) BETWEEN 16 AND 128),
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash) = 32),
    response_bookmark_id UUID,
    response_work_id UUID,
    response_chapter_id UUID,
    response_segment_anchor VARCHAR(160),
    response_label VARCHAR(160),
    response_created_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_reader_bookmark_idempotency_response_chapter
        FOREIGN KEY (response_work_id, response_chapter_id)
        REFERENCES chapters(work_id, id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, idempotency_key)
);
CREATE INDEX ix_reader_bookmark_idempotency_expiry
    ON reader_bookmark_idempotency (expires_at);
--rollback DROP TABLE reader_bookmark_idempotency;
--rollback DROP TABLE reader_bookmarks;
