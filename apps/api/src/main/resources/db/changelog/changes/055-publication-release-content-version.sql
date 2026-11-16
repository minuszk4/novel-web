--liquibase formatted sql

--changeset novelweb:publication-055-release-content-version splitStatements:false rollbackSplitStatements:false
CREATE OR REPLACE FUNCTION guard_publication_work_content_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    publication_content_changed BOOLEAN;
BEGIN
    publication_content_changed :=
        ROW(
            NEW.owner_user_id,
            NEW.library_id,
            NEW.title,
            NEW.author,
            NEW.source_language,
            NEW.description,
            NEW.metadata_schema_version,
            NEW.metadata,
            NEW.authoring_metadata_version,
            NEW.source_kind,
            NEW.status,
            NEW.deleted_at
        ) IS DISTINCT FROM ROW(
            OLD.owner_user_id,
            OLD.library_id,
            OLD.title,
            OLD.author,
            OLD.source_language,
            OLD.description,
            OLD.metadata_schema_version,
            OLD.metadata,
            OLD.authoring_metadata_version,
            OLD.source_kind,
            OLD.status,
            OLD.deleted_at
        );

    IF publication_content_changed THEN
        NEW.publication_content_version := OLD.publication_content_version + 1;
    ELSIF NEW.publication_content_version IS DISTINCT FROM OLD.publication_content_version THEN
        IF pg_trigger_depth() <= 1
           OR NEW.publication_content_version <> OLD.publication_content_version + 1 THEN
            RAISE EXCEPTION 'Publication content versions are maintained by PostgreSQL'
                USING ERRCODE = '55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

--rollback CREATE OR REPLACE FUNCTION guard_publication_work_content_version() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE publication_content_changed BOOLEAN; BEGIN publication_content_changed := ROW(NEW.owner_user_id, NEW.library_id, NEW.title, NEW.author, NEW.source_language, NEW.description, NEW.metadata_schema_version, NEW.metadata, NEW.authoring_metadata_version, NEW.source_kind, NEW.visibility, NEW.status, NEW.deleted_at) IS DISTINCT FROM ROW(OLD.owner_user_id, OLD.library_id, OLD.title, OLD.author, OLD.source_language, OLD.description, OLD.metadata_schema_version, OLD.metadata, OLD.authoring_metadata_version, OLD.source_kind, OLD.visibility, OLD.status, OLD.deleted_at); IF publication_content_changed THEN NEW.publication_content_version := OLD.publication_content_version + 1; ELSIF NEW.publication_content_version IS DISTINCT FROM OLD.publication_content_version THEN IF pg_trigger_depth() <= 1 OR NEW.publication_content_version <> OLD.publication_content_version + 1 THEN RAISE EXCEPTION 'Publication content versions are maintained by PostgreSQL' USING ERRCODE = '55000'; END IF; END IF; RETURN NEW; END; $$;
