--liquibase formatted sql

--changeset novelweb:workspace-001-root-and-members
CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    scope_version BIGINT NOT NULL DEFAULT 1 CHECK (scope_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE workspace_members (
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role VARCHAR(16) NOT NULL CHECK (role IN ('owner', 'admin', 'editor', 'reader')),
    status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (workspace_id, user_id),
    CHECK ((status = 'active' AND revoked_at IS NULL)
        OR (status = 'revoked' AND revoked_at IS NOT NULL)),
    CHECK (role <> 'owner' OR status = 'active')
);
--rollback DROP TABLE workspace_members;
--rollback DROP TABLE workspaces;

--changeset novelweb:workspace-002-membership-indexes
CREATE UNIQUE INDEX uq_workspace_active_owner
    ON workspace_members (workspace_id) WHERE role = 'owner' AND status = 'active';
CREATE INDEX ix_workspace_members_active_user
    ON workspace_members (user_id, workspace_id) WHERE status = 'active';
CREATE INDEX ix_workspace_members_active_workspace
    ON workspace_members (workspace_id, user_id) WHERE status = 'active';
--rollback DROP INDEX ix_workspace_members_active_workspace;
--rollback DROP INDEX ix_workspace_members_active_user;
--rollback DROP INDEX uq_workspace_active_owner;

--changeset novelweb:workspace-003-owner-assertion splitStatements:false
CREATE FUNCTION enforce_workspace_has_active_owner() RETURNS trigger
LANGUAGE plpgsql AS $function$
DECLARE
    target_workspace_id UUID;
    active_owner_count INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'workspaces' THEN
        target_workspace_id := NEW.id;
    ELSE
        target_workspace_id := COALESCE(NEW.workspace_id, OLD.workspace_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM workspaces WHERE id = target_workspace_id) THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO active_owner_count
    FROM workspace_members
    WHERE workspace_id = target_workspace_id AND role = 'owner' AND status = 'active';

    IF active_owner_count <> 1 THEN
        RAISE EXCEPTION 'workspace % must have exactly one active owner', target_workspace_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_workspace_exactly_one_active_owner';
    END IF;
    RETURN NULL;
END
$function$;
--rollback DROP FUNCTION enforce_workspace_has_active_owner();

--changeset novelweb:workspace-004-owner-triggers
CREATE CONSTRAINT TRIGGER workspace_requires_active_owner
    AFTER INSERT ON workspaces DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_has_active_owner();
CREATE CONSTRAINT TRIGGER workspace_member_requires_active_owner
    AFTER INSERT OR UPDATE OR DELETE ON workspace_members DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_has_active_owner();
--rollback DROP TRIGGER workspace_member_requires_active_owner ON workspace_members;
--rollback DROP TRIGGER workspace_requires_active_owner ON workspaces;

--changeset novelweb:workspace-005-library-scope
ALTER TABLE libraries ADD COLUMN workspace_id UUID REFERENCES workspaces(id) ON DELETE RESTRICT;
ALTER TABLE libraries ADD CONSTRAINT ck_workspace_library_not_default CHECK (workspace_id IS NULL OR NOT is_default);
DROP INDEX uq_libraries_default_per_owner;
DROP INDEX uq_libraries_owner_name;
CREATE UNIQUE INDEX uq_libraries_default_per_owner
    ON libraries (owner_user_id) WHERE workspace_id IS NULL AND is_default AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_libraries_owner_name
    ON libraries (owner_user_id, lower(name)) WHERE workspace_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_libraries_workspace_name
    ON libraries (workspace_id, lower(name)) WHERE workspace_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_libraries_workspace_created
    ON libraries (workspace_id, is_default DESC, created_at DESC, id DESC)
    WHERE workspace_id IS NOT NULL AND deleted_at IS NULL;
--rollback DROP INDEX ix_libraries_workspace_created;
--rollback DROP INDEX uq_libraries_workspace_name;
--rollback DROP INDEX uq_libraries_owner_name;
--rollback DROP INDEX uq_libraries_default_per_owner;
--rollback CREATE UNIQUE INDEX uq_libraries_owner_name ON libraries (owner_user_id, lower(name)) WHERE deleted_at IS NULL;
--rollback CREATE UNIQUE INDEX uq_libraries_default_per_owner ON libraries (owner_user_id) WHERE is_default AND deleted_at IS NULL;
--rollback ALTER TABLE libraries DROP CONSTRAINT ck_workspace_library_not_default;
--rollback ALTER TABLE libraries DROP COLUMN workspace_id;

--changeset novelweb:workspace-006-access-function splitStatements:false
CREATE FUNCTION can_access_library(p_library_id UUID, p_user_id UUID, p_write BOOLEAN DEFAULT FALSE)
RETURNS BOOLEAN
LANGUAGE SQL STABLE
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM libraries l
        WHERE l.id = p_library_id AND l.deleted_at IS NULL
          AND (
              (l.workspace_id IS NULL AND l.owner_user_id = p_user_id)
              OR (l.workspace_id IS NOT NULL AND EXISTS (
                  SELECT 1
                  FROM workspace_members m
                  WHERE m.workspace_id = l.workspace_id
                    AND m.user_id = p_user_id
                    AND m.status = 'active'
                    AND (NOT p_write OR m.role IN ('owner', 'admin', 'editor'))
              ))
          )
    )
$function$;
--rollback DROP FUNCTION can_access_library(UUID, UUID, BOOLEAN);
