--liquibase formatted sql

--changeset novelweb:publication-067-translation-adaptation-scope splitStatements:false rollbackSplitStatements:false
ALTER TABLE publication_scope_access_approvals
    DROP CONSTRAINT publication_scope_access_approvals_scope_code_check;
ALTER TABLE publication_scope_access_approvals
    ADD CONSTRAINT publication_scope_access_approvals_scope_code_check
    CHECK (scope_code IN (
        'PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE', 'TRANSLATION_ADAPTATION'
    ));

ALTER TABLE publication_request_scope_snapshots
    DROP CONSTRAINT publication_request_scope_snapshots_scope_code_check;
ALTER TABLE publication_request_scope_snapshots
    ADD CONSTRAINT publication_request_scope_snapshots_scope_code_check
    CHECK (scope_code IN (
        'PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE', 'TRANSLATION_ADAPTATION'
    ));

CREATE OR REPLACE FUNCTION publication_work_scope_snapshot(p_work_id UUID, p_scope_code VARCHAR)
RETURNS JSONB
LANGUAGE SQL
STABLE
AS $$
    SELECT CASE
        WHEN p_scope_code NOT IN (
            'PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE', 'TRANSLATION_ADAPTATION'
        ) THEN NULL
        WHEN p_scope_code = 'TRANSLATION_ADAPTATION' THEN jsonb_build_object(
            'scope', p_scope_code,
            'work', jsonb_build_object(
                'id', w.id,
                'ownerUserId', w.owner_user_id,
                'title', w.title,
                'author', w.author,
                'sourceLanguage', w.source_language,
                'sourceKind', w.source_kind,
                'authoringMetadataVersion', w.authoring_metadata_version
            ),
            'chapters', (
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'id', c.id,
                            'ordinal', c.ordinal,
                            'title', c.title,
                            'sourceLanguage', c.source_language,
                            'revisionId', c.source_revision_id,
                            'contentHash', encode(r.content_hash, 'hex')
                        ) ORDER BY c.ordinal, c.id
                    ),
                    '[]'::JSONB
                )
                FROM chapters c
                JOIN chapter_revisions r
                  ON r.id = c.source_revision_id AND r.chapter_id = c.id
                WHERE c.work_id = w.id
                  AND c.status = 'ready'
                  AND c.deleted_at IS NULL
                  AND c.source_revision_id IS NOT NULL
            )
        )
        ELSE jsonb_build_object(
            'scope', p_scope_code,
            'work', jsonb_build_object(
                'id', w.id,
                'ownerUserId', w.owner_user_id,
                'libraryId', w.library_id,
                'title', w.title,
                'author', w.author,
                'sourceLanguage', w.source_language,
                'description', w.description,
                'metadataSchemaVersion', w.metadata_schema_version,
                'metadata', w.metadata,
                'authoringMetadataVersion', w.authoring_metadata_version
            ),
            'chapters', (
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object(
                            'id', c.id,
                            'ordinal', c.ordinal,
                            'title', c.title,
                            'sourceLanguage', c.source_language,
                            'revisionId', CASE
                                WHEN p_scope_code = 'PUBLIC_METADATA' THEN NULL
                                ELSE c.source_revision_id
                            END,
                            'contentHash', CASE
                                WHEN p_scope_code = 'PUBLIC_METADATA' OR r.id IS NULL THEN NULL
                                ELSE encode(r.content_hash, 'hex')
                            END,
                            'segments', CASE
                                WHEN p_scope_code = 'PUBLIC_METADATA' THEN NULL
                                ELSE COALESCE((
                                    SELECT jsonb_agg(
                                        jsonb_build_object(
                                            'ordinal', s.ordinal,
                                            'type', s.segment_type,
                                            'anchor', s.stable_anchor,
                                            'checksum', encode(s.checksum, 'hex')
                                        ) ORDER BY s.ordinal
                                    )
                                    FROM segments s
                                    WHERE s.chapter_revision_id = r.id
                                ), '[]'::JSONB)
                            END
                        ) ORDER BY c.ordinal, c.id
                    ),
                    '[]'::JSONB
                )
                FROM chapters c
                LEFT JOIN chapter_revisions r
                  ON r.id = c.source_revision_id AND r.chapter_id = c.id
                WHERE c.work_id = w.id
                  AND c.status = 'ready'
                  AND c.deleted_at IS NULL
                  AND c.source_revision_id IS NOT NULL
            )
        )
    END
    FROM works w
    WHERE w.id = p_work_id;
$$;
-- TRANSLATION_ADAPTATION approvals bind to the current authored source structure and hashes.
-- Exact accepted translated text is separately bound by immutable translation release events.
-- Public access is still denied until all required scope approvals and owner release conditions hold.
--rollback CREATE OR REPLACE FUNCTION publication_work_scope_snapshot(p_work_id UUID, p_scope_code VARCHAR) RETURNS JSONB LANGUAGE SQL STABLE AS $$ SELECT CASE WHEN p_scope_code NOT IN ('PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE') THEN NULL ELSE jsonb_build_object('scope', p_scope_code, 'work', jsonb_build_object('id', w.id, 'ownerUserId', w.owner_user_id, 'libraryId', w.library_id, 'title', w.title, 'author', w.author, 'sourceLanguage', w.source_language, 'description', w.description, 'metadataSchemaVersion', w.metadata_schema_version, 'metadata', w.metadata, 'authoringMetadataVersion', w.authoring_metadata_version), 'chapters', (SELECT COALESCE(jsonb_agg(jsonb_build_object('id', c.id, 'ordinal', c.ordinal, 'title', c.title, 'sourceLanguage', c.source_language, 'revisionId', CASE WHEN p_scope_code = 'PUBLIC_METADATA' THEN NULL ELSE c.source_revision_id END, 'contentHash', CASE WHEN p_scope_code = 'PUBLIC_METADATA' OR r.id IS NULL THEN NULL ELSE encode(r.content_hash, 'hex') END, 'segments', CASE WHEN p_scope_code = 'PUBLIC_METADATA' THEN NULL ELSE COALESCE((SELECT jsonb_agg(jsonb_build_object('ordinal', s.ordinal, 'type', s.segment_type, 'anchor', s.stable_anchor, 'checksum', encode(s.checksum, 'hex')) ORDER BY s.ordinal) FROM segments s WHERE s.chapter_revision_id = r.id), '[]'::JSONB) END) ORDER BY c.ordinal, c.id), '[]'::JSONB) FROM chapters c LEFT JOIN chapter_revisions r ON r.id = c.source_revision_id AND r.chapter_id = c.id WHERE c.work_id = w.id AND c.status = 'ready' AND c.deleted_at IS NULL AND c.source_revision_id IS NOT NULL)) END FROM works w WHERE w.id = p_work_id; $$;
--rollback ALTER TABLE publication_request_scope_snapshots DROP CONSTRAINT publication_request_scope_snapshots_scope_code_check;
--rollback ALTER TABLE publication_request_scope_snapshots ADD CONSTRAINT publication_request_scope_snapshots_scope_code_check CHECK (scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE'));
--rollback ALTER TABLE publication_scope_access_approvals DROP CONSTRAINT publication_scope_access_approvals_scope_code_check;
--rollback ALTER TABLE publication_scope_access_approvals ADD CONSTRAINT publication_scope_access_approvals_scope_code_check CHECK (scope_code IN ('PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE'));
