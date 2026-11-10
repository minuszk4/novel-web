--liquibase formatted sql

--changeset novelweb:library-001-libraries
CREATE TABLE libraries (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    visibility VARCHAR(16) NOT NULL DEFAULT 'private' CHECK (visibility = 'private'),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (owner_user_id, id),
    CHECK ((deleted_at IS NULL) OR NOT is_default)
);
--rollback DROP TABLE libraries;

--changeset novelweb:library-002-library-constraints
CREATE UNIQUE INDEX uq_libraries_default_per_owner
    ON libraries (owner_user_id) WHERE is_default AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_libraries_owner_name
    ON libraries (owner_user_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX ix_libraries_owner_created
    ON libraries (owner_user_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
--rollback DROP INDEX ix_libraries_owner_created;
--rollback DROP INDEX uq_libraries_owner_name;
--rollback DROP INDEX uq_libraries_default_per_owner;

--changeset novelweb:library-003-works
CREATE TABLE works (
    id UUID PRIMARY KEY,
    library_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    title VARCHAR(300) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 300),
    author VARCHAR(200) NOT NULL DEFAULT '',
    source_language VARCHAR(35) NOT NULL DEFAULT 'und'
        CHECK (length(btrim(source_language)) BETWEEN 2 AND 35),
    description TEXT,
    source_kind VARCHAR(16) NOT NULL DEFAULT 'imported'
        CHECK (source_kind IN ('imported', 'authored')),
    visibility VARCHAR(16) NOT NULL DEFAULT 'private'
        CHECK (visibility IN ('private', 'unlisted', 'public')),
    status VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'archived', 'deleted')),
    metadata_schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (metadata_schema_version > 0),
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    source_hash BYTEA CHECK (source_hash IS NULL OR octet_length(source_hash) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (owner_user_id, id),
    CONSTRAINT fk_works_owner_library
        FOREIGN KEY (owner_user_id, library_id)
        REFERENCES libraries (owner_user_id, id) ON DELETE RESTRICT,
    CHECK ((status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL))
);
--rollback DROP TABLE works;

--changeset novelweb:library-004-work-indexes
CREATE UNIQUE INDEX uq_works_owner_source_hash
    ON works (owner_user_id, source_hash)
    WHERE source_hash IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_works_library_created
    ON works (owner_user_id, library_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_works_library_title
    ON works (owner_user_id, library_id, lower(title), id)
    WHERE deleted_at IS NULL;
--rollback DROP INDEX ix_works_library_title;
--rollback DROP INDEX ix_works_library_created;
--rollback DROP INDEX uq_works_owner_source_hash;

--changeset novelweb:library-005-volumes
CREATE TABLE volumes (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    title VARCHAR(300) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (work_id, id),
    UNIQUE (work_id, ordinal)
);
--rollback DROP TABLE volumes;

--changeset novelweb:library-006-chapters
CREATE TABLE chapters (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE RESTRICT,
    volume_id UUID,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    title VARCHAR(300) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 300),
    source_language VARCHAR(35) NOT NULL DEFAULT 'und'
        CHECK (length(btrim(source_language)) BETWEEN 2 AND 35),
    source_revision_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'ready', 'unavailable', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (work_id, id),
    UNIQUE (work_id, ordinal),
    CONSTRAINT fk_chapters_volume_work
        FOREIGN KEY (work_id, volume_id) REFERENCES volumes(work_id, id) ON DELETE RESTRICT,
    CHECK ((status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL))
);
--rollback DROP TABLE chapters;

--changeset novelweb:library-007-chapter-revisions
CREATE TABLE chapter_revisions (
    id UUID PRIMARY KEY,
    chapter_id UUID NOT NULL REFERENCES chapters(id) ON DELETE RESTRICT,
    revision_no INTEGER NOT NULL CHECK (revision_no > 0),
    source_format VARCHAR(24) NOT NULL
        CHECK (source_format IN ('plain_text', 'markdown', 'xhtml', 'epub_xhtml')),
    normalized_content TEXT NOT NULL CHECK (octet_length(normalized_content) <= 10485760),
    content_hash BYTEA NOT NULL CHECK (octet_length(content_hash) = 32),
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (chapter_id, revision_no),
    UNIQUE (chapter_id, id)
);
ALTER TABLE chapters ADD CONSTRAINT fk_chapters_current_revision
    FOREIGN KEY (id, source_revision_id) REFERENCES chapter_revisions(chapter_id, id)
    DEFERRABLE INITIALLY DEFERRED;
--rollback ALTER TABLE chapters DROP CONSTRAINT fk_chapters_current_revision;
--rollback DROP TABLE chapter_revisions;

--changeset novelweb:library-008-chapter-revision-index
CREATE INDEX ix_chapter_revisions_latest
    ON chapter_revisions (chapter_id, revision_no DESC);
CREATE UNIQUE INDEX uq_chapter_revision_content_hash
    ON chapter_revisions (chapter_id, content_hash);
--rollback DROP INDEX uq_chapter_revision_content_hash;
--rollback DROP INDEX ix_chapter_revisions_latest;

--changeset novelweb:library-009-segments
CREATE TABLE segments (
    id UUID PRIMARY KEY,
    chapter_revision_id UUID NOT NULL REFERENCES chapter_revisions(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    segment_type VARCHAR(16) NOT NULL
        CHECK (segment_type IN ('paragraph', 'heading', 'quote', 'image_ref')),
    source_text TEXT NOT NULL CHECK (octet_length(source_text) <= 1048576),
    stable_anchor VARCHAR(160) NOT NULL
        CHECK (stable_anchor ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'),
    checksum BYTEA NOT NULL CHECK (octet_length(checksum) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (chapter_revision_id, ordinal),
    UNIQUE (chapter_revision_id, stable_anchor)
);
--rollback DROP TABLE segments;

--changeset novelweb:library-010-assets
CREATE TABLE assets (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    library_id UUID NOT NULL,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    content_hash BYTEA NOT NULL CHECK (octet_length(content_hash) = 32),
    media_type VARCHAR(160) NOT NULL CHECK (length(btrim(media_type)) BETWEEN 1 AND 160),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 5368709120),
    original_name VARCHAR(512) NOT NULL CHECK (length(btrim(original_name)) BETWEEN 1 AND 512),
    status VARCHAR(16) NOT NULL DEFAULT 'staged'
        CHECK (status IN ('staged', 'available', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (owner_user_id, id),
    CONSTRAINT fk_assets_owner_library
        FOREIGN KEY (owner_user_id, library_id)
        REFERENCES libraries (owner_user_id, id) ON DELETE RESTRICT,
    CHECK ((status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL))
);
--rollback DROP TABLE assets;

--changeset novelweb:library-011-asset-dedup-index
CREATE UNIQUE INDEX uq_assets_owner_content_hash
    ON assets (owner_user_id, content_hash) WHERE deleted_at IS NULL;
CREATE INDEX ix_assets_library_created
    ON assets (owner_user_id, library_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
--rollback DROP INDEX ix_assets_library_created;
--rollback DROP INDEX uq_assets_owner_content_hash;
