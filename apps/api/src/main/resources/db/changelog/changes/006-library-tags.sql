--liquibase formatted sql

--changeset novelweb:library-tags-001-table
CREATE TABLE tags (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    slug VARCHAR(80) NOT NULL CHECK (length(btrim(slug)) BETWEEN 1 AND 80 AND slug = lower(slug)),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (owner_user_id, id)
);
--rollback DROP TABLE tags;

--changeset novelweb:library-tags-002-indexes
CREATE UNIQUE INDEX uq_tags_owner_slug
    ON tags (owner_user_id, slug) WHERE deleted_at IS NULL;
CREATE INDEX ix_tags_owner_created
    ON tags (owner_user_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
--rollback DROP INDEX ix_tags_owner_created;
--rollback DROP INDEX uq_tags_owner_slug;

--changeset novelweb:library-tags-003-work-tags
CREATE TABLE work_tags (
    owner_user_id UUID NOT NULL,
    work_id UUID NOT NULL,
    tag_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (owner_user_id, work_id, tag_id),
    CONSTRAINT fk_work_tags_owner_work
        FOREIGN KEY (owner_user_id, work_id)
        REFERENCES works (owner_user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_tags_owner_tag
        FOREIGN KEY (owner_user_id, tag_id)
        REFERENCES tags (owner_user_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_work_tags_owner_tag_work
    ON work_tags (owner_user_id, tag_id, work_id);
--rollback DROP TABLE work_tags;

--changeset novelweb:library-tags-004-search-indexes
CREATE INDEX ix_libraries_owner_default_created
    ON libraries (owner_user_id, is_default DESC, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_works_library_status_created
    ON works (owner_user_id, library_id, status, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
--rollback DROP INDEX ix_works_library_status_created;
--rollback DROP INDEX ix_libraries_owner_default_created;
