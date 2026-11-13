--liquibase formatted sql

--changeset novelweb:glossary-001-glossaries
ALTER TABLE works ADD CONSTRAINT uq_works_library_id_id UNIQUE (library_id, id);

CREATE TABLE glossaries (
    id UUID PRIMARY KEY,
    owner_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    scope_type VARCHAR(16) NOT NULL
        CHECK (scope_type IN ('USER_GLOBAL', 'LIBRARY', 'WORK', 'VOLUME', 'CHAPTER')),
    library_id UUID REFERENCES libraries(id) ON DELETE RESTRICT,
    work_id UUID,
    volume_id UUID,
    chapter_id UUID,
    source_language VARCHAR(35) NOT NULL
        CHECK (length(btrim(source_language)) BETWEEN 2 AND 35),
    target_language VARCHAR(35) NOT NULL
        CHECK (length(btrim(target_language)) BETWEEN 2 AND 35),
    active_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_glossaries_library_work
        FOREIGN KEY (library_id, work_id) REFERENCES works(library_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_glossaries_work_volume
        FOREIGN KEY (work_id, volume_id) REFERENCES volumes(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_glossaries_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_glossaries_scope_target CHECK (
        (scope_type = 'USER_GLOBAL' AND library_id IS NULL AND work_id IS NULL
            AND volume_id IS NULL AND chapter_id IS NULL)
        OR (scope_type = 'LIBRARY' AND library_id IS NOT NULL AND work_id IS NULL
            AND volume_id IS NULL AND chapter_id IS NULL)
        OR (scope_type = 'WORK' AND library_id IS NOT NULL AND work_id IS NOT NULL
            AND volume_id IS NULL AND chapter_id IS NULL)
        OR (scope_type = 'VOLUME' AND library_id IS NOT NULL AND work_id IS NOT NULL
            AND volume_id IS NOT NULL AND chapter_id IS NULL)
        OR (scope_type = 'CHAPTER' AND library_id IS NOT NULL AND work_id IS NOT NULL
            AND volume_id IS NULL AND chapter_id IS NOT NULL)
    ),
    CONSTRAINT ck_glossaries_owner_scope CHECK (
        (scope_type = 'USER_GLOBAL' AND owner_user_id IS NOT NULL)
        OR (scope_type <> 'USER_GLOBAL' AND owner_user_id IS NULL)
    )
);
CREATE INDEX ix_glossaries_owner_created
    ON glossaries (owner_user_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_glossaries_library_scope
    ON glossaries (library_id, scope_type, created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND library_id IS NOT NULL;
CREATE INDEX ix_glossaries_work_scope
    ON glossaries (work_id, scope_type, created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND work_id IS NOT NULL;
--rollback DROP TABLE glossaries;
--rollback ALTER TABLE works DROP CONSTRAINT uq_works_library_id_id;

--changeset novelweb:glossary-002-revisions
CREATE TABLE glossary_revisions (
    id UUID PRIMARY KEY,
    glossary_id UUID NOT NULL REFERENCES glossaries(id) ON DELETE CASCADE,
    revision_no INTEGER NOT NULL CHECK (revision_no > 0),
    parent_revision_id UUID,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_glossary_revisions_number UNIQUE (glossary_id, revision_no),
    CONSTRAINT uq_glossary_revisions_id_glossary UNIQUE (glossary_id, id),
    CONSTRAINT fk_glossary_revisions_parent
        FOREIGN KEY (glossary_id, parent_revision_id)
        REFERENCES glossary_revisions (glossary_id, id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX ix_glossary_revisions_latest
    ON glossary_revisions (glossary_id, revision_no DESC);
ALTER TABLE glossaries ADD CONSTRAINT fk_glossaries_active_revision
    FOREIGN KEY (id, active_revision_id)
    REFERENCES glossary_revisions (glossary_id, id)
    DEFERRABLE INITIALLY DEFERRED;
--rollback ALTER TABLE glossaries DROP CONSTRAINT fk_glossaries_active_revision;
--rollback DROP TABLE glossary_revisions;

--changeset novelweb:glossary-003-terms-and-aliases
CREATE TABLE glossary_terms (
    id UUID NOT NULL,
    revision_id UUID NOT NULL REFERENCES glossary_revisions(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    source_term VARCHAR(512) NOT NULL CHECK (length(btrim(source_term)) BETWEEN 1 AND 512),
    target_term VARCHAR(4096) CHECK (target_term IS NULL OR length(target_term) <= 4096),
    priority INTEGER NOT NULL DEFAULT 0 CHECK (priority BETWEEN -1000000 AND 1000000),
    case_policy VARCHAR(16) NOT NULL CHECK (case_policy IN ('SENSITIVE', 'INSENSITIVE')),
    match_mode VARCHAR(24) NOT NULL CHECK (match_mode = 'WHOLE_PHRASE'),
    do_not_translate BOOLEAN NOT NULL DEFAULT FALSE,
    context_note VARCHAR(2000) CHECK (context_note IS NULL OR length(context_note) <= 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (revision_id, id),
    UNIQUE (revision_id, ordinal),
    CHECK (do_not_translate OR (target_term IS NOT NULL AND length(btrim(target_term)) > 0))
);
CREATE INDEX ix_glossary_terms_source
    ON glossary_terms (revision_id, lower(source_term));

CREATE TABLE glossary_aliases (
    revision_id UUID NOT NULL,
    term_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    alias_text VARCHAR(512) NOT NULL CHECK (length(btrim(alias_text)) BETWEEN 1 AND 512),
    PRIMARY KEY (revision_id, term_id, ordinal),
    CONSTRAINT fk_glossary_aliases_term
        FOREIGN KEY (revision_id, term_id)
        REFERENCES glossary_terms (revision_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_glossary_aliases_revision_text
    ON glossary_aliases (revision_id, lower(alias_text));
--rollback DROP TABLE glossary_aliases;
--rollback DROP TABLE glossary_terms;

--changeset novelweb:glossary-004-immutable-snapshots splitStatements:false
CREATE FUNCTION reject_glossary_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE'
       AND pg_trigger_depth() > 1
       AND OLD.created_by_user_id IS NOT NULL
       AND NEW.created_by_user_id IS NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Glossary revision snapshots are immutable'
        USING ERRCODE = '55000';
END;
$$;
--rollback DROP FUNCTION reject_glossary_snapshot_mutation();

--changeset novelweb:glossary-005-immutable-snapshot-triggers
CREATE TRIGGER trg_glossary_revision_immutable
    BEFORE UPDATE OR DELETE ON glossary_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_glossary_snapshot_mutation();
CREATE TRIGGER trg_glossary_term_immutable
    BEFORE UPDATE OR DELETE ON glossary_terms
    FOR EACH ROW EXECUTE FUNCTION reject_glossary_snapshot_mutation();
CREATE TRIGGER trg_glossary_alias_immutable
    BEFORE UPDATE OR DELETE ON glossary_aliases
    FOR EACH ROW EXECUTE FUNCTION reject_glossary_snapshot_mutation();
--rollback DROP TRIGGER trg_glossary_alias_immutable ON glossary_aliases;
--rollback DROP TRIGGER trg_glossary_term_immutable ON glossary_terms;
--rollback DROP TRIGGER trg_glossary_revision_immutable ON glossary_revisions;

--changeset novelweb:glossary-006-definition-guard splitStatements:false
CREATE FUNCTION guard_glossary_definition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id
       OR OLD.scope_type IS DISTINCT FROM NEW.scope_type
       OR OLD.library_id IS DISTINCT FROM NEW.library_id
       OR OLD.work_id IS DISTINCT FROM NEW.work_id
       OR OLD.volume_id IS DISTINCT FROM NEW.volume_id
       OR OLD.chapter_id IS DISTINCT FROM NEW.chapter_id
       OR OLD.source_language IS DISTINCT FROM NEW.source_language
       OR OLD.target_language IS DISTINCT FROM NEW.target_language THEN
        RAISE EXCEPTION 'Glossary owner, scope and language pair are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_glossary_definition_immutable
    BEFORE UPDATE ON glossaries
    FOR EACH ROW EXECUTE FUNCTION guard_glossary_definition();
--rollback DROP TRIGGER trg_glossary_definition_immutable ON glossaries;
--rollback DROP FUNCTION guard_glossary_definition();
