--liquibase formatted sql

--changeset novelweb:publication-011-acceptance-receipt-scope-unique
ALTER TABLE publication_rights_policy_acceptances
    ADD CONSTRAINT uq_publication_rights_acceptance_receipt_scope
    UNIQUE (id, user_id, work_id);
--rollback ALTER TABLE publication_rights_policy_acceptances DROP CONSTRAINT uq_publication_rights_acceptance_receipt_scope;

--changeset novelweb:publication-012-acceptance-idempotency
CREATE TABLE publication_rights_policy_acceptance_idempotency (
    user_id UUID NOT NULL,
    work_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_sha256 BYTEA NOT NULL CHECK (octet_length(request_sha256) = 32),
    acceptance_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, work_id, idempotency_key),
    CONSTRAINT fk_publication_rights_idempotency_receipt
        FOREIGN KEY (acceptance_id, user_id, work_id)
        REFERENCES publication_rights_policy_acceptances(id, user_id, work_id) ON DELETE RESTRICT
);
--rollback DROP TABLE publication_rights_policy_acceptance_idempotency;

--changeset novelweb:publication-013-acceptance-idempotency-immutability-function splitStatements:false
CREATE FUNCTION reject_publication_rights_idempotency_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Publication-rights idempotency records are append-only'
        USING ERRCODE = '55000';
END;
$$;
--rollback DROP FUNCTION reject_publication_rights_idempotency_mutation();

--changeset novelweb:publication-014-acceptance-idempotency-immutability-triggers
CREATE TRIGGER trg_publication_rights_idempotency_immutable
    BEFORE UPDATE OR DELETE ON publication_rights_policy_acceptance_idempotency
    FOR EACH ROW EXECUTE FUNCTION reject_publication_rights_idempotency_mutation();
CREATE TRIGGER trg_publication_rights_idempotency_truncate_immutable
    BEFORE TRUNCATE ON publication_rights_policy_acceptance_idempotency
    FOR EACH STATEMENT EXECUTE FUNCTION reject_publication_rights_idempotency_mutation();
--rollback DROP TRIGGER trg_publication_rights_idempotency_truncate_immutable ON publication_rights_policy_acceptance_idempotency;
--rollback DROP TRIGGER trg_publication_rights_idempotency_immutable ON publication_rights_policy_acceptance_idempotency;
