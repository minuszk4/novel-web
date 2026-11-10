--liquibase formatted sql

--changeset novelweb:identity-016-policy-documents
CREATE TABLE policy_documents (
    policy_type VARCHAR(32) NOT NULL
        CHECK (policy_type IN ('TERMS', 'PRIVACY_NOTICE')),
    version VARCHAR(64) NOT NULL
        CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    effective_at TIMESTAMPTZ NOT NULL,
    document_markdown TEXT NOT NULL
        CHECK (length(btrim(document_markdown)) > 0)
        CHECK (octet_length(document_markdown) <= 1048576),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (policy_type, version),
    UNIQUE (policy_type, effective_at)
);
--rollback DROP TABLE policy_documents;

--changeset novelweb:identity-017-policy-document-current-index
CREATE INDEX ix_policy_documents_type_effective
    ON policy_documents (policy_type, effective_at DESC);
--rollback DROP INDEX ix_policy_documents_type_effective;

--changeset novelweb:identity-018-policy-acceptance-document-fk
ALTER TABLE policy_acceptances
    ADD CONSTRAINT fk_policy_acceptance_document
    FOREIGN KEY (policy_type, version)
    REFERENCES policy_documents (policy_type, version)
    ON DELETE RESTRICT
    NOT VALID;
--rollback ALTER TABLE policy_acceptances DROP CONSTRAINT fk_policy_acceptance_document;
