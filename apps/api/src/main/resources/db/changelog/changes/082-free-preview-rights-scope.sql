--liquibase formatted sql

--changeset novelweb:publication-082-free-preview-rights-scope splitStatements:false rollbackSplitStatements:false
ALTER TABLE publication_scope_access_approvals
    DROP CONSTRAINT publication_scope_access_approvals_scope_code_check;
ALTER TABLE publication_scope_access_approvals
    ADD CONSTRAINT publication_scope_access_approvals_scope_code_check
    CHECK (scope_code IN (
        'PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE',
        'TRANSLATION_ADAPTATION', 'FREE_PREVIEW'
    ));

ALTER TABLE publication_request_scope_snapshots
    DROP CONSTRAINT publication_request_scope_snapshots_scope_code_check;
ALTER TABLE publication_request_scope_snapshots
    ADD CONSTRAINT publication_request_scope_snapshots_scope_code_check
    CHECK (scope_code IN (
        'PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE',
        'TRANSLATION_ADAPTATION', 'FREE_PREVIEW'
    ));

CREATE FUNCTION require_commercial_scope_for_free_preview()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.scope_code = 'FREE_PREVIEW'
       AND NOT EXISTS (
           SELECT 1
             FROM publication_request_scope_snapshots sale
            WHERE sale.request_id = NEW.request_id
              AND sale.scope_code = 'COMMERCIAL_SALE'
       ) THEN
        RAISE EXCEPTION 'A free-preview request requires commercial-sale scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER publication_request_free_preview_requires_sale
    BEFORE INSERT ON publication_request_scope_snapshots
    FOR EACH ROW EXECUTE FUNCTION require_commercial_scope_for_free_preview();

ALTER FUNCTION publication_work_scope_snapshot(UUID, VARCHAR)
    RENAME TO publication_work_scope_snapshot_before_free_preview;

CREATE FUNCTION publication_work_scope_snapshot(p_work_id UUID, p_scope_code VARCHAR)
RETURNS JSONB
LANGUAGE SQL
STABLE
AS $$
    SELECT CASE
        WHEN p_scope_code <> 'FREE_PREVIEW' THEN
            publication_work_scope_snapshot_before_free_preview(p_work_id, p_scope_code)
        ELSE (
            SELECT CASE
                WHEN source.snapshot IS NULL THEN NULL
                ELSE jsonb_build_object(
                    'scope', 'FREE_PREVIEW',
                    'previewPolicyVersion', 'free-preview-v1',
                    'previewPercent', 10,
                    'measurement', 'UNICODE_CODE_POINTS',
                    'work', source.snapshot -> 'work',
                    'chapters', source.snapshot -> 'chapters'
                )
            END
            FROM (
                SELECT publication_work_scope_snapshot_before_free_preview(
                    p_work_id, 'CHAPTER_TEXT') AS snapshot
            ) source
        )
    END;
$$;

ALTER FUNCTION publication_work_scope_snapshot_for_chapter(UUID, VARCHAR, UUID)
    RENAME TO publication_work_scope_snapshot_for_chapter_before_free_preview;

CREATE FUNCTION publication_work_scope_snapshot_for_chapter(
    p_work_id UUID,
    p_scope_code VARCHAR,
    p_candidate_chapter_id UUID
)
RETURNS JSONB
LANGUAGE SQL
STABLE
AS $$
    SELECT CASE
        WHEN p_scope_code <> 'FREE_PREVIEW' THEN
            publication_work_scope_snapshot_for_chapter_before_free_preview(
                p_work_id, p_scope_code, p_candidate_chapter_id)
        ELSE (
            SELECT CASE
                WHEN source.snapshot IS NULL THEN NULL
                ELSE jsonb_build_object(
                    'scope', 'FREE_PREVIEW',
                    'previewPolicyVersion', 'free-preview-v1',
                    'previewPercent', 10,
                    'measurement', 'UNICODE_CODE_POINTS',
                    'work', source.snapshot -> 'work',
                    'chapters', source.snapshot -> 'chapters'
                )
            END
            FROM (
                SELECT publication_work_scope_snapshot_for_chapter_before_free_preview(
                    p_work_id, 'CHAPTER_TEXT', p_candidate_chapter_id) AS snapshot
            ) source
        )
    END;
$$;

--rollback DROP TRIGGER publication_request_free_preview_requires_sale ON publication_request_scope_snapshots;
--rollback DROP FUNCTION require_commercial_scope_for_free_preview();
--rollback DROP FUNCTION publication_work_scope_snapshot_for_chapter(UUID, VARCHAR, UUID);
--rollback ALTER FUNCTION publication_work_scope_snapshot_for_chapter_before_free_preview(UUID, VARCHAR, UUID) RENAME TO publication_work_scope_snapshot_for_chapter;
--rollback DROP FUNCTION publication_work_scope_snapshot(UUID, VARCHAR);
--rollback ALTER FUNCTION publication_work_scope_snapshot_before_free_preview(UUID, VARCHAR) RENAME TO publication_work_scope_snapshot;
--rollback ALTER TABLE publication_request_scope_snapshots DROP CONSTRAINT publication_request_scope_snapshots_scope_code_check;
--rollback ALTER TABLE publication_request_scope_snapshots ADD CONSTRAINT publication_request_scope_snapshots_scope_code_check CHECK (scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE', 'TRANSLATION_ADAPTATION'));
--rollback ALTER TABLE publication_scope_access_approvals DROP CONSTRAINT publication_scope_access_approvals_scope_code_check;
--rollback ALTER TABLE publication_scope_access_approvals ADD CONSTRAINT publication_scope_access_approvals_scope_code_check CHECK (scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE', 'TRANSLATION_ADAPTATION'));
