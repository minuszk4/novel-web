--liquibase formatted sql

--changeset novelweb:translation-035-candidate-segment-edit-revisions
CREATE TABLE translation_candidate_segment_edits (
    candidate_id UUID NOT NULL,
    source_segment_id UUID NOT NULL,
    edit_revision INTEGER NOT NULL CHECK (edit_revision > 0),
    editor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    translated_text TEXT NOT NULL
        CHECK (length(btrim(translated_text)) > 0 AND octet_length(translated_text) <= 1048576),
    translated_sha256 BYTEA NOT NULL CHECK (octet_length(translated_sha256) = 32),
    idempotency_key_sha256 BYTEA NOT NULL CHECK (octet_length(idempotency_key_sha256) = 32),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (candidate_id, source_segment_id, edit_revision),
    CONSTRAINT uq_translation_candidate_segment_edit_idempotency
        UNIQUE (editor_user_id, candidate_id, source_segment_id, idempotency_key_sha256),
    CONSTRAINT fk_translation_candidate_segment_edit_source
        FOREIGN KEY (candidate_id, source_segment_id)
        REFERENCES translation_candidate_segments(candidate_id, source_segment_id) ON DELETE CASCADE
);
CREATE INDEX ix_translation_candidate_segment_edits_latest
    ON translation_candidate_segment_edits (candidate_id, source_segment_id, edit_revision DESC);
--rollback DROP TABLE translation_candidate_segment_edits;

--changeset novelweb:translation-036-candidate-segment-edit-guard splitStatements:false
CREATE FUNCTION guard_translation_candidate_segment_edit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Translation candidate segment edits are append-only'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        -- Account erasure and candidate cleanup may cascade-delete private edit history.
        IF pg_trigger_depth() > 1 THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Translation candidate segment edits are append-only'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;
--rollback DROP FUNCTION guard_translation_candidate_segment_edit_mutation();

--changeset novelweb:translation-037-candidate-segment-edit-guard-trigger
CREATE TRIGGER trg_translation_candidate_segment_edit_guard
    BEFORE UPDATE OR DELETE ON translation_candidate_segment_edits
    FOR EACH ROW EXECUTE FUNCTION guard_translation_candidate_segment_edit_mutation();
--rollback DROP TRIGGER trg_translation_candidate_segment_edit_guard ON translation_candidate_segment_edits;
