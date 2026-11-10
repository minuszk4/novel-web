--liquibase formatted sql

--changeset novelweb:workspace-tags-001-assigned-tags
ALTER TABLE work_tags ADD COLUMN assigned_by_user_id UUID;
UPDATE work_tags SET assigned_by_user_id = owner_user_id;
ALTER TABLE work_tags ALTER COLUMN assigned_by_user_id SET NOT NULL;
ALTER TABLE work_tags DROP CONSTRAINT fk_work_tags_owner_tag;
ALTER TABLE work_tags ADD CONSTRAINT fk_work_tags_assigned_by_tag
    FOREIGN KEY (assigned_by_user_id, tag_id)
    REFERENCES tags (owner_user_id, id) ON DELETE RESTRICT;
CREATE INDEX ix_work_tags_assigned_tag_work
    ON work_tags (assigned_by_user_id, tag_id, work_id);
CREATE INDEX ix_work_tags_work_tag
    ON work_tags (work_id, tag_id);
--rollback DELETE FROM work_tags WHERE owner_user_id <> assigned_by_user_id;
--rollback DROP INDEX ix_work_tags_work_tag;
--rollback DROP INDEX ix_work_tags_assigned_tag_work;
--rollback ALTER TABLE work_tags DROP CONSTRAINT fk_work_tags_assigned_by_tag;
--rollback ALTER TABLE work_tags ADD CONSTRAINT fk_work_tags_owner_tag
--rollback     FOREIGN KEY (owner_user_id, tag_id) REFERENCES tags (owner_user_id, id) ON DELETE RESTRICT;
--rollback ALTER TABLE work_tags DROP COLUMN assigned_by_user_id;
