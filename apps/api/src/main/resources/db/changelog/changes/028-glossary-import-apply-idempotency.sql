--liquibase formatted sql

--changeset novelweb:glossary-005-import-apply-idempotency
CREATE TABLE glossary_import_apply_idempotency (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    glossary_id UUID NOT NULL REFERENCES glossaries(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{16,128}$'),
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    revision_id UUID NOT NULL,
    glossary_version BIGINT NOT NULL CHECK (glossary_version > 1),
    skipped_rows INTEGER NOT NULL CHECK (skipped_rows >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, glossary_id, idempotency_key),
    CONSTRAINT fk_glossary_import_apply_revision
        FOREIGN KEY (glossary_id, revision_id)
        REFERENCES glossary_revisions (glossary_id, id)
        ON DELETE CASCADE
);
CREATE INDEX ix_glossary_import_apply_created
    ON glossary_import_apply_idempotency (created_at);

CREATE TABLE glossary_import_apply_rows (
    user_id UUID NOT NULL,
    glossary_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    row_number INTEGER NOT NULL CHECK (row_number > 0),
    action VARCHAR(16) NOT NULL CHECK (action IN ('ADD', 'REPLACE', 'MERGE', 'SKIP')),
    term_id UUID,
    PRIMARY KEY (user_id, glossary_id, idempotency_key, row_number),
    CONSTRAINT fk_glossary_import_apply_rows_receipt
        FOREIGN KEY (user_id, glossary_id, idempotency_key)
        REFERENCES glossary_import_apply_idempotency (user_id, glossary_id, idempotency_key)
        ON DELETE CASCADE,
    CHECK ((action = 'SKIP' AND term_id IS NULL) OR (action <> 'SKIP' AND term_id IS NOT NULL))
);
--rollback DROP TABLE glossary_import_apply_rows;
--rollback DROP TABLE glossary_import_apply_idempotency;
