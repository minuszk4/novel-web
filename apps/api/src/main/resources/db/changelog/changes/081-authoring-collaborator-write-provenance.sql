--liquibase formatted sql

--changeset novelweb:authoring-081-collaborator-write-provenance
-- Idempotency rows record the authenticated actor, who may be an EDIT collaborator.
-- Application transactions still verify the active manuscript grant before every write.
ALTER TABLE authoring_chapter_creations
    DROP CONSTRAINT fk_authoring_chapter_creation_owner_work;
ALTER TABLE authoring_chapter_creations
    ADD CONSTRAINT fk_authoring_chapter_creation_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_authoring_chapter_creation_work
        FOREIGN KEY (work_id) REFERENCES works(id) ON DELETE RESTRICT;

ALTER TABLE authoring_chapter_revision_writes
    DROP CONSTRAINT fk_authoring_revision_write_owner_work;
ALTER TABLE authoring_chapter_revision_writes
    ADD CONSTRAINT fk_authoring_revision_write_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_authoring_revision_write_work
        FOREIGN KEY (work_id) REFERENCES works(id) ON DELETE RESTRICT;

--rollback ALTER TABLE authoring_chapter_revision_writes DROP CONSTRAINT fk_authoring_revision_write_work;
--rollback ALTER TABLE authoring_chapter_revision_writes DROP CONSTRAINT fk_authoring_revision_write_actor;
--rollback ALTER TABLE authoring_chapter_revision_writes ADD CONSTRAINT fk_authoring_revision_write_owner_work FOREIGN KEY (actor_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT;
--rollback ALTER TABLE authoring_chapter_creations DROP CONSTRAINT fk_authoring_chapter_creation_work;
--rollback ALTER TABLE authoring_chapter_creations DROP CONSTRAINT fk_authoring_chapter_creation_actor;
--rollback ALTER TABLE authoring_chapter_creations ADD CONSTRAINT fk_authoring_chapter_creation_owner_work FOREIGN KEY (actor_user_id, work_id) REFERENCES works(owner_user_id, id) ON DELETE RESTRICT;
