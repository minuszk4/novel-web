--liquibase formatted sql

--changeset novelweb:library-state-001-reading-status
CREATE TABLE library_reading_states (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('reading', 'want-to-read', 'paused', 'completed', 'dropped')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, work_id)
);
CREATE INDEX ix_library_reading_states_work_updated
    ON library_reading_states (work_id, updated_at DESC, user_id);
--rollback DROP TABLE library_reading_states;

--changeset novelweb:library-state-002-collections
CREATE TABLE library_collections (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(40) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (owner_user_id, id)
);
CREATE UNIQUE INDEX uq_library_collections_owner_name
    ON library_collections (owner_user_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX ix_library_collections_owner_created
    ON library_collections (owner_user_id, created_at, id) WHERE deleted_at IS NULL;
--rollback DROP TABLE library_collections;

--changeset novelweb:library-state-003-collection-works
CREATE TABLE library_collection_works (
    owner_user_id UUID NOT NULL,
    collection_id UUID NOT NULL,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (collection_id, work_id),
    CONSTRAINT fk_library_collection_works_owner_collection
        FOREIGN KEY (owner_user_id, collection_id)
        REFERENCES library_collections (owner_user_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_library_collection_works_owner_work
    ON library_collection_works (owner_user_id, work_id, collection_id);
--rollback DROP TABLE library_collection_works;
