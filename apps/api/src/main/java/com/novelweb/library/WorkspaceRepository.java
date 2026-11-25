package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Persistence operations for workspace and member lifecycle. */
@Repository
@Profile("postgres")
public class WorkspaceRepository {

  private final DataSource dataSource;

  public WorkspaceRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public WorkspaceRecord createWorkspace(UUID actorUserId, String name) {
    return createWorkspace(actorUserId, UUID.randomUUID(), name);
  }

  public WorkspaceRecord createWorkspace(UUID actorUserId, UUID workspaceId, String name) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    return inTransaction(
        "Unable to create a workspace",
        connection -> {
          requireActiveUser(connection, actorUserId);
          try (PreparedStatement insert =
              connection.prepareStatement(
                  """
                    INSERT INTO workspaces (id, name, created_by_user_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    RETURNING id, name, created_by_user_id, scope_version, created_at
                    """)) {
            insert.setObject(1, workspaceId);
            insert.setString(2, name);
            insert.setObject(3, actorUserId);
            try (ResultSet row = insert.executeQuery()) {
              if (row.next()) {
                WorkspaceRecord created = workspace(row);
                insertMember(connection, workspaceId, actorUserId, Role.OWNER);
                WorkspaceMembershipAuditRepository.append(
                    connection,
                    workspaceId,
                    actorUserId,
                    actorUserId,
                    WorkspaceMembershipAuditRepository.Action.WORKSPACE_CREATED,
                    WorkspaceMembershipAuditRepository.Reason.INITIAL_OWNER,
                    null,
                    Role.OWNER,
                    null,
                    MemberStatus.ACTIVE);
                return created;
              }
            }
            return findIdempotentCreation(connection, actorUserId, workspaceId, name)
                .orElseThrow(WorkspaceMemberConflictException::new);
          }
        });
  }

  private static Optional<WorkspaceRecord> findIdempotentCreation(
      Connection connection, UUID actorUserId, UUID workspaceId, String name) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT w.id, w.name, w.created_by_user_id, w.scope_version, w.created_at
                FROM workspaces w
                JOIN workspace_members m ON m.workspace_id = w.id
                WHERE w.id = ? AND w.name = ? AND w.created_by_user_id = ?
                  AND m.user_id = ? AND m.role = 'owner' AND m.status = 'active'
                """)) {
      select.setObject(1, workspaceId);
      select.setString(2, name);
      select.setObject(3, actorUserId);
      select.setObject(4, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(workspace(row)) : Optional.empty();
      }
    }
  }

  public List<WorkspaceMembershipRecord> listWorkspaces(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT w.id, w.name, w.created_by_user_id, w.scope_version, w.created_at,
                           member.role, member.joined_at
                    FROM workspace_members member
                    JOIN workspaces w ON w.id = member.workspace_id
                    WHERE member.user_id = ? AND member.status = 'active'
                    ORDER BY w.created_at DESC, w.id
                    """)) {
      select.setObject(1, actorUserId);
      try (ResultSet rows = select.executeQuery()) {
        List<WorkspaceMembershipRecord> workspaces = new ArrayList<>();
        while (rows.next()) {
          workspaces.add(
              new WorkspaceMembershipRecord(
                  workspace(rows),
                  Role.fromValue(rows.getString("role")),
                  rows.getObject("joined_at", OffsetDateTime.class).toInstant()));
        }
        return List.copyOf(workspaces);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list workspace memberships", exception);
    }
  }

  public MemberRecord putMember(UUID actorUserId, UUID workspaceId, UUID memberUserId, Role role) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    Objects.requireNonNull(memberUserId, "member user id is required");
    Objects.requireNonNull(role, "member role is required");
    if (role == Role.OWNER) throw new OwnershipTransferRequiredException();
    return inTransaction(
        "Unable to set a workspace member role",
        connection -> {
          requireManager(connection, actorUserId, workspaceId);
          requireActiveUser(connection, memberUserId);
          MemberRecord existing = findMemberForUpdate(connection, workspaceId, memberUserId);
          if (existing == null) {
            try (PreparedStatement insert =
                connection.prepareStatement(
                    """
                        INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, revoked_at)
                        VALUES (?, ?, ?, 'active', clock_timestamp(), NULL)
                        RETURNING workspace_id, user_id, role, status, joined_at, revoked_at
                        """)) {
              insert.setObject(1, workspaceId);
              insert.setObject(2, memberUserId);
              insert.setString(3, role.value());
              try (ResultSet row = insert.executeQuery()) {
                row.next();
                MemberRecord created = member(row);
                bumpScopeVersion(connection, workspaceId);
                WorkspaceMembershipAuditRepository.append(
                    connection,
                    workspaceId,
                    actorUserId,
                    memberUserId,
                    WorkspaceMembershipAuditRepository.Action.MEMBER_GRANTED,
                    WorkspaceMembershipAuditRepository.Reason.MANAGER_REQUEST,
                    null,
                    role,
                    null,
                    MemberStatus.ACTIVE);
                return created;
              }
            }
          }
          if (existing.status() == MemberStatus.ACTIVE) {
            if (existing.role() == Role.OWNER) throw new OwnershipTransferRequiredException();
            if (existing.role() == role) return existing;
            try (PreparedStatement update =
                connection.prepareStatement(
                    """
                        UPDATE workspace_members SET role = ?, updated_at = clock_timestamp()
                        WHERE workspace_id = ? AND user_id = ? AND status = 'active'
                        RETURNING workspace_id, user_id, role, status, joined_at, revoked_at
                        """)) {
              update.setString(1, role.value());
              update.setObject(2, workspaceId);
              update.setObject(3, memberUserId);
              try (ResultSet row = update.executeQuery()) {
                row.next();
                MemberRecord changed = member(row);
                bumpScopeVersion(connection, workspaceId);
                WorkspaceMembershipAuditRepository.append(
                    connection,
                    workspaceId,
                    actorUserId,
                    memberUserId,
                    WorkspaceMembershipAuditRepository.Action.MEMBER_ROLE_CHANGED,
                    WorkspaceMembershipAuditRepository.Reason.MANAGER_REQUEST,
                    existing.role(),
                    role,
                    MemberStatus.ACTIVE,
                    MemberStatus.ACTIVE);
                return changed;
              }
            }
          }
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                      UPDATE workspace_members
                      SET role = ?, status = 'active', joined_at = clock_timestamp(),
                          revoked_at = NULL, updated_at = clock_timestamp()
                      WHERE workspace_id = ? AND user_id = ? AND status = 'revoked'
                      RETURNING workspace_id, user_id, role, status, joined_at, revoked_at
                      """)) {
            update.setString(1, role.value());
            update.setObject(2, workspaceId);
            update.setObject(3, memberUserId);
            try (ResultSet row = update.executeQuery()) {
              row.next();
              MemberRecord reactivated = member(row);
              bumpScopeVersion(connection, workspaceId);
              WorkspaceMembershipAuditRepository.append(
                  connection,
                  workspaceId,
                  actorUserId,
                  memberUserId,
                  WorkspaceMembershipAuditRepository.Action.MEMBER_REACTIVATED,
                  WorkspaceMembershipAuditRepository.Reason.MANAGER_REQUEST,
                  existing.role(),
                  role,
                  MemberStatus.REVOKED,
                  MemberStatus.ACTIVE);
              return reactivated;
            }
          }
        });
  }

  private static MemberRecord findMemberForUpdate(
      Connection connection, UUID workspaceId, UUID memberUserId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT workspace_id, user_id, role, status, joined_at, revoked_at
                FROM workspace_members WHERE workspace_id = ? AND user_id = ? FOR UPDATE
                """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, memberUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? member(row) : null;
      }
    }
  }

  public MemberRecord addMember(UUID actorUserId, UUID workspaceId, UUID memberUserId, Role role) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    Objects.requireNonNull(memberUserId, "member user id is required");
    Objects.requireNonNull(role, "member role is required");
    if (role == Role.OWNER) throw new OwnershipTransferRequiredException();
    return inTransaction(
        "Unable to add a workspace member",
        connection -> {
          requireManager(connection, actorUserId, workspaceId);
          requireActiveUser(connection, memberUserId);
          MemberRecord existing = findMemberForUpdate(connection, workspaceId, memberUserId);
          if (existing != null && existing.status() != MemberStatus.REVOKED) {
            throw new WorkspaceMemberConflictException();
          }
          String sql =
              existing == null
                  ? """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    VALUES (?, ?, ?)
                    RETURNING workspace_id, user_id, role, status, joined_at, revoked_at
                    """
                  : """
                    UPDATE workspace_members
                       SET role = ?, status = 'active', joined_at = clock_timestamp(),
                           revoked_at = NULL, updated_at = clock_timestamp()
                     WHERE workspace_id = ? AND user_id = ? AND status = 'revoked'
                     RETURNING workspace_id, user_id, role, status, joined_at, revoked_at
                    """;
          try (PreparedStatement mutation = connection.prepareStatement(sql)) {
            if (existing == null) {
              mutation.setObject(1, workspaceId);
              mutation.setObject(2, memberUserId);
              mutation.setString(3, role.value());
            } else {
              mutation.setString(1, role.value());
              mutation.setObject(2, workspaceId);
              mutation.setObject(3, memberUserId);
            }
            try (ResultSet row = mutation.executeQuery()) {
              if (!row.next()) throw new WorkspaceMemberConflictException();
              MemberRecord changed = member(row);
              bumpScopeVersion(connection, workspaceId);
              WorkspaceMembershipAuditRepository.append(
                  connection,
                  workspaceId,
                  actorUserId,
                  memberUserId,
                  existing == null
                      ? WorkspaceMembershipAuditRepository.Action.MEMBER_GRANTED
                      : WorkspaceMembershipAuditRepository.Action.MEMBER_REACTIVATED,
                  WorkspaceMembershipAuditRepository.Reason.MANAGER_REQUEST,
                  existing == null ? null : existing.role(),
                  role,
                  existing == null ? null : MemberStatus.REVOKED,
                  MemberStatus.ACTIVE);
              return changed;
            }
          }
        });
  }

  public MemberRecord changeMemberRole(
      UUID actorUserId, UUID workspaceId, UUID memberUserId, Role role) {
    Objects.requireNonNull(role, "member role is required");
    if (role == Role.OWNER) throw new OwnershipTransferRequiredException();
    return inTransaction(
        "Unable to change a workspace member role",
        connection -> {
          requireManager(connection, actorUserId, workspaceId);
          MemberRecord existing = findMemberForUpdate(connection, workspaceId, memberUserId);
          if (existing == null
              || existing.status() != MemberStatus.ACTIVE
              || existing.role() == Role.OWNER) {
            throw new WorkspaceMemberNotFoundException();
          }
          if (existing.role() == role) return existing;
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                    UPDATE workspace_members
                    SET role = ?, updated_at = clock_timestamp()
                    WHERE workspace_id = ? AND user_id = ? AND status = 'active' AND role <> 'owner'
                    RETURNING workspace_id, user_id, role, status, joined_at, revoked_at
                    """)) {
            update.setString(1, role.value());
            update.setObject(2, workspaceId);
            update.setObject(3, memberUserId);
            try (ResultSet row = update.executeQuery()) {
              if (!row.next()) throw new WorkspaceMemberNotFoundException();
              MemberRecord changed = member(row);
              bumpScopeVersion(connection, workspaceId);
              WorkspaceMembershipAuditRepository.append(
                  connection,
                  workspaceId,
                  actorUserId,
                  memberUserId,
                  WorkspaceMembershipAuditRepository.Action.MEMBER_ROLE_CHANGED,
                  WorkspaceMembershipAuditRepository.Reason.MANAGER_REQUEST,
                  existing.role(),
                  role,
                  MemberStatus.ACTIVE,
                  MemberStatus.ACTIVE);
              return changed;
            }
          }
        });
  }

  public WorkspaceRecord transferOwnership(
      UUID actorUserId, UUID workspaceId, UUID newOwnerUserId) {
    Objects.requireNonNull(newOwnerUserId, "new owner user id is required");
    return inTransaction(
        "Unable to transfer workspace ownership",
        connection -> {
          requireOwner(connection, actorUserId, workspaceId);
          if (actorUserId.equals(newOwnerUserId)) {
            throw new OwnershipTransferRequiredException();
          }
          MemberRecord nextOwner = findMemberForUpdate(connection, workspaceId, newOwnerUserId);
          if (nextOwner == null || nextOwner.status() != MemberStatus.ACTIVE) {
            throw new WorkspaceMemberNotFoundException();
          }
          try (PreparedStatement demote =
              connection.prepareStatement(
                  """
                    UPDATE workspace_members
                    SET role = 'admin', updated_at = clock_timestamp()
                    WHERE workspace_id = ? AND user_id = ? AND role = 'owner' AND status = 'active'
                    """)) {
            demote.setObject(1, workspaceId);
            demote.setObject(2, actorUserId);
            if (demote.executeUpdate() != 1) throw new WorkspaceAccessDeniedException();
          }
          try (PreparedStatement promote =
              connection.prepareStatement(
                  """
                    UPDATE workspace_members
                    SET role = 'owner', updated_at = clock_timestamp()
                    WHERE workspace_id = ? AND user_id = ? AND status = 'active'
                    """)) {
            promote.setObject(1, workspaceId);
            promote.setObject(2, newOwnerUserId);
            if (promote.executeUpdate() != 1) throw new WorkspaceMemberNotFoundException();
          }
          bumpScopeVersion(connection, workspaceId);
          WorkspaceMembershipAuditRepository.append(
              connection,
              workspaceId,
              actorUserId,
              newOwnerUserId,
              WorkspaceMembershipAuditRepository.Action.OWNERSHIP_TRANSFERRED,
              WorkspaceMembershipAuditRepository.Reason.OWNER_HANDOVER,
              nextOwner.role(),
              Role.OWNER,
              MemberStatus.ACTIVE,
              MemberStatus.ACTIVE);
          return findWorkspace(connection, workspaceId)
              .orElseThrow(WorkspaceNotFoundException::new);
        });
  }

  public MemberRecord revokeMember(UUID actorUserId, UUID workspaceId, UUID memberUserId) {
    return inTransaction(
        "Unable to revoke a workspace member",
        connection -> {
          requireManager(connection, actorUserId, workspaceId);
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                    UPDATE workspace_members
                    SET status = 'revoked', revoked_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE workspace_id = ? AND user_id = ? AND status = 'active' AND role <> 'owner'
                    RETURNING workspace_id, user_id, role, status, joined_at, revoked_at
                    """)) {
            update.setObject(1, workspaceId);
            update.setObject(2, memberUserId);
            try (ResultSet row = update.executeQuery()) {
              if (!row.next()) {
                if (isActiveOwner(connection, workspaceId, memberUserId)) {
                  throw new WorkspaceOwnerCannotBeRevokedException();
                }
                throw new WorkspaceMemberNotFoundException();
              }
              MemberRecord revoked = member(row);
              bumpScopeVersion(connection, workspaceId);
              WorkspaceMembershipAuditRepository.append(
                  connection,
                  workspaceId,
                  actorUserId,
                  memberUserId,
                  WorkspaceMembershipAuditRepository.Action.MEMBER_REVOKED,
                  WorkspaceMembershipAuditRepository.Reason.MANAGER_REQUEST,
                  revoked.role(),
                  revoked.role(),
                  MemberStatus.ACTIVE,
                  MemberStatus.REVOKED);
              return revoked;
            }
          }
        });
  }

  public Optional<WorkspaceRecord> findWorkspace(UUID actorUserId, UUID workspaceId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT w.id, w.name, w.created_by_user_id, w.scope_version, w.created_at
                     FROM workspaces w
                     JOIN workspace_members m ON m.workspace_id = w.id
                     WHERE w.id = ? AND m.user_id = ? AND m.status = 'active'
                     """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(workspace(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the workspace", exception);
    }
  }

  public List<MemberRecord> listMembers(UUID actorUserId, UUID workspaceId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    try (Connection connection = dataSource.getConnection()) {
      Role actorRole = activeRole(connection, workspaceId, actorUserId);
      try (PreparedStatement select =
          connection.prepareStatement(
              """
                    SELECT workspace_id, user_id, role, status, joined_at, revoked_at
                    FROM workspace_members
                    WHERE workspace_id = ? AND (? IN ('owner', 'admin') OR status = 'active')
                    ORDER BY CASE status WHEN 'active' THEN 0 ELSE 1 END, joined_at, user_id
                    """)) {
        select.setObject(1, workspaceId);
        select.setString(2, actorRole.value());
        try (ResultSet rows = select.executeQuery()) {
          List<MemberRecord> members = new ArrayList<>();
          while (rows.next()) members.add(member(rows));
          return List.copyOf(members);
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load workspace members", exception);
    }
  }

  private static Role activeRole(Connection connection, UUID workspaceId, UUID userId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT role FROM workspace_members
                WHERE workspace_id = ? AND user_id = ? AND status = 'active'
                """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new WorkspaceAccessDeniedException();
        return Role.fromValue(row.getString("role"));
      }
    }
  }

  private static void insertMember(Connection connection, UUID workspaceId, UUID userId, Role role)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO workspace_members (workspace_id, user_id, role)
                VALUES (?, ?, ?)
                """)) {
      insert.setObject(1, workspaceId);
      insert.setObject(2, userId);
      insert.setString(3, role.value());
      insert.executeUpdate();
    }
  }

  private static void requireManager(Connection connection, UUID actorUserId, UUID workspaceId)
      throws SQLException {
    Role role = lockActorRole(connection, actorUserId, workspaceId);
    if (role != Role.OWNER && role != Role.ADMIN) throw new WorkspaceAccessDeniedException();
  }

  private static void requireOwner(Connection connection, UUID actorUserId, UUID workspaceId)
      throws SQLException {
    if (lockActorRole(connection, actorUserId, workspaceId) != Role.OWNER) {
      throw new WorkspaceAccessDeniedException();
    }
  }

  private static Role lockActorRole(Connection connection, UUID actorUserId, UUID workspaceId)
      throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR UPDATE")) {
      lock.setObject(1, workspaceId);
      try (ResultSet row = lock.executeQuery()) {
        if (!row.next()) throw new WorkspaceNotFoundException();
      }
    }
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT role FROM workspace_members
                WHERE workspace_id = ? AND user_id = ? AND status = 'active'
                FOR UPDATE
                """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new WorkspaceAccessDeniedException();
        return Role.fromValue(row.getString("role"));
      }
    }
  }

  private static void requireActiveMember(Connection connection, UUID workspaceId, UUID userId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1 FROM workspace_members
                WHERE workspace_id = ? AND user_id = ? AND status = 'active'
                """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new WorkspaceMemberNotFoundException();
      }
    }
  }

  private static boolean isActiveOwner(Connection connection, UUID workspaceId, UUID userId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1 FROM workspace_members
                WHERE workspace_id = ? AND user_id = ? AND role = 'owner' AND status = 'active'
                """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static void requireActiveUser(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new WorkspaceMemberNotFoundException();
      }
    }
  }

  private static void bumpScopeVersion(Connection connection, UUID workspaceId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE workspaces
                SET scope_version = scope_version + 1, updated_at = clock_timestamp()
                WHERE id = ?
                """)) {
      update.setObject(1, workspaceId);
      if (update.executeUpdate() != 1) throw new WorkspaceNotFoundException();
    }
  }

  private static Optional<WorkspaceRecord> findWorkspace(Connection connection, UUID workspaceId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, name, created_by_user_id, scope_version, created_at
                FROM workspaces WHERE id = ?
                """)) {
      select.setObject(1, workspaceId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(workspace(row)) : Optional.empty();
      }
    }
  }

  private <T> T inTransaction(String failureMessage, SqlTransaction<T> action) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = action.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkspaceRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new WorkspaceMemberConflictException();
      throw new IllegalStateException(failureMessage, exception);
    }
  }

  private static WorkspaceRecord workspace(ResultSet row) throws SQLException {
    return new WorkspaceRecord(
        row.getObject("id", UUID.class),
        row.getString("name"),
        row.getObject("created_by_user_id", UUID.class),
        row.getLong("scope_version"),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static MemberRecord member(ResultSet row) throws SQLException {
    OffsetDateTime revokedAt = row.getObject("revoked_at", OffsetDateTime.class);
    return new MemberRecord(
        row.getObject("workspace_id", UUID.class),
        row.getObject("user_id", UUID.class),
        Role.fromValue(row.getString("role")),
        MemberStatus.fromValue(row.getString("status")),
        row.getObject("joined_at", OffsetDateTime.class).toInstant(),
        revokedAt == null ? null : revokedAt.toInstant());
  }

  @FunctionalInterface
  private interface SqlTransaction<T> {
    T run(Connection connection) throws SQLException;
  }

  public enum Role {
    OWNER("owner"),
    ADMIN("admin"),
    EDITOR("editor"),
    READER("reader");

    private final String value;

    Role(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }

    private static Role fromValue(String value) {
      for (Role role : values()) if (role.value.equals(value)) return role;
      throw new IllegalStateException("Unknown workspace role");
    }
  }

  public enum MemberStatus {
    ACTIVE("active"),
    REVOKED("revoked");

    private final String value;

    MemberStatus(String value) {
      this.value = value;
    }

    private static MemberStatus fromValue(String value) {
      for (MemberStatus status : values()) if (status.value.equals(value)) return status;
      throw new IllegalStateException("Unknown workspace member status");
    }
  }

  public record WorkspaceRecord(
      UUID id, String name, UUID createdByUserId, long scopeVersion, java.time.Instant createdAt) {}

  public record WorkspaceMembershipRecord(
      WorkspaceRecord workspace, Role role, java.time.Instant joinedAt) {}

  public record MemberRecord(
      UUID workspaceId,
      UUID userId,
      Role role,
      MemberStatus status,
      java.time.Instant joinedAt,
      java.time.Instant revokedAt) {}

  public static class WorkspaceRepositoryException extends RuntimeException {}

  public static class WorkspaceNotFoundException extends WorkspaceRepositoryException {}

  public static class WorkspaceAccessDeniedException extends WorkspaceRepositoryException {}

  public static class WorkspaceMemberNotFoundException extends WorkspaceRepositoryException {}

  public static class WorkspaceMemberConflictException extends WorkspaceRepositoryException {}

  public static class WorkspaceOwnerCannotBeRevokedException extends WorkspaceRepositoryException {}

  public static class OwnershipTransferRequiredException extends WorkspaceRepositoryException {}
}
