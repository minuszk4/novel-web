--liquibase formatted sql

--changeset novelweb:reader-085-paired-mode-preference
ALTER TABLE reader_preferences
    DROP CONSTRAINT reader_preferences_schema_version_check;

ALTER TABLE reader_preferences
    ADD COLUMN paired_mode_by_default BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE reader_preferences
SET schema_version = 2;

ALTER TABLE reader_preferences
    ALTER COLUMN schema_version SET DEFAULT 2;

ALTER TABLE reader_preferences
    ADD CONSTRAINT reader_preferences_schema_version_check CHECK (schema_version = 2);

--rollback UPDATE reader_preferences SET schema_version = 1;
--rollback ALTER TABLE reader_preferences DROP CONSTRAINT reader_preferences_schema_version_check;
--rollback ALTER TABLE reader_preferences ADD CONSTRAINT reader_preferences_schema_version_check CHECK (schema_version = 1);
--rollback ALTER TABLE reader_preferences ALTER COLUMN schema_version SET DEFAULT 1;
--rollback ALTER TABLE reader_preferences DROP COLUMN paired_mode_by_default;
