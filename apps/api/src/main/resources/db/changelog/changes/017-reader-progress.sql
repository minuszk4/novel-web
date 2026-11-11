--liquibase formatted sql

--changeset novelweb:reader-001-progress
CREATE TABLE reader_progress (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE RESTRICT,
    chapter_id UUID,
    segment_anchor VARCHAR(160)
        CHECK (segment_anchor IS NULL OR segment_anchor ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'),
    scroll_ratio DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (scroll_ratio >= 0 AND scroll_ratio <= 1),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, work_id),
    CONSTRAINT fk_reader_progress_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_reader_progress_empty_position
        CHECK (chapter_id IS NOT NULL OR (segment_anchor IS NULL AND scroll_ratio = 0)),
    CONSTRAINT ck_reader_progress_anchor_requires_chapter
        CHECK (segment_anchor IS NULL OR chapter_id IS NOT NULL),
    CONSTRAINT ck_reader_progress_timestamp_version
        CHECK ((version = 0 AND updated_at IS NULL) OR (version > 0 AND updated_at IS NOT NULL))
);
CREATE INDEX ix_reader_progress_user_updated
    ON reader_progress (user_id, updated_at DESC, work_id);
--rollback DROP TABLE reader_progress;
