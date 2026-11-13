--liquibase formatted sql

--changeset novelweb:glossary-007-publication-audit splitStatements:false rollbackSplitStatements:false
CREATE TABLE glossary_publication_events (
    id UUID PRIMARY KEY,
    glossary_id UUID NOT NULL REFERENCES glossaries(id) ON DELETE CASCADE,
    revision_id UUID NOT NULL,
    previous_revision_id UUID,
    published_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    glossary_version BIGINT NOT NULL CHECK (glossary_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_glossary_publication_revision
        FOREIGN KEY (glossary_id, revision_id)
        REFERENCES glossary_revisions (glossary_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_glossary_publication_events_history
    ON glossary_publication_events (glossary_id, created_at DESC, id DESC);

CREATE FUNCTION reject_glossary_publication_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE'
       AND pg_trigger_depth() > 1
       AND OLD.published_by_user_id IS NOT NULL
       AND NEW.published_by_user_id IS NULL
       AND (to_jsonb(NEW) - 'published_by_user_id') = (to_jsonb(OLD) - 'published_by_user_id') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Glossary publication events are append-only'
        USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_glossary_publication_event_immutable
    BEFORE UPDATE OR DELETE ON glossary_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_glossary_publication_event_mutation();
--rollback DROP TRIGGER trg_glossary_publication_event_immutable ON glossary_publication_events;
--rollback DROP FUNCTION reject_glossary_publication_event_mutation();
--rollback DROP TABLE glossary_publication_events;
