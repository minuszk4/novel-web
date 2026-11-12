--liquibase formatted sql

--changeset novelweb:reader-004-annotations
CREATE TABLE reader_annotations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    chapter_id UUID NOT NULL,
    chapter_revision_id UUID REFERENCES chapter_revisions(id) ON DELETE SET NULL,
    start_segment_anchor VARCHAR(160) NOT NULL
        CHECK (start_segment_anchor ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'),
    start_codepoint_offset INTEGER NOT NULL CHECK (start_codepoint_offset >= 0),
    end_segment_anchor VARCHAR(160) NOT NULL
        CHECK (end_segment_anchor ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'),
    end_codepoint_offset INTEGER NOT NULL CHECK (end_codepoint_offset >= 0),
    quote_snapshot VARCHAR(2000) NOT NULL
        CHECK (char_length(btrim(quote_snapshot)) BETWEEN 1 AND 2000),
    note_text VARCHAR(2000) NOT NULL DEFAULT ''
        CHECK (char_length(note_text) <= 2000),
    color_key VARCHAR(16) NOT NULL DEFAULT 'amber'
        CHECK (color_key IN ('amber', 'mint', 'sky', 'rose')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_reader_annotation_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_reader_annotation_same_segment_range
        CHECK (start_segment_anchor <> end_segment_anchor OR start_codepoint_offset < end_codepoint_offset)
);

CREATE UNIQUE INDEX uq_reader_annotation_range
    ON reader_annotations (
        user_id,
        chapter_id,
        chapter_revision_id,
        start_segment_anchor,
        start_codepoint_offset,
        end_segment_anchor,
        end_codepoint_offset
    )
    WHERE chapter_revision_id IS NOT NULL;
CREATE INDEX ix_reader_annotation_user_work_updated
    ON reader_annotations (user_id, work_id, updated_at DESC, id DESC);
CREATE INDEX ix_reader_annotation_user_work_chapter_revision
    ON reader_annotations (user_id, work_id, chapter_id, chapter_revision_id);

--rollback DROP TABLE reader_annotations;
