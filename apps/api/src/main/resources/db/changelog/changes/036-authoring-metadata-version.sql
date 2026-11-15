--liquibase formatted sql

--changeset novelweb:authoring-002-manuscript-metadata-version
ALTER TABLE works
    ADD COLUMN authoring_metadata_version BIGINT NOT NULL DEFAULT 1
        CHECK (authoring_metadata_version > 0);

--rollback ALTER TABLE works DROP COLUMN authoring_metadata_version;
