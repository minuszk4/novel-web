package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Reads the workspace-scoped append-only membership history and writes rows in caller transactions.
 */
@Repository
@Profile("postgres")
public class WorkspaceMembershipAuditRepository {

  private final DataSource dataSource;

  public WorkspaceMembershipAuditRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Page list(UUID actorUserId, UUID workspaceId, Long beforeEventId, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    if (beforeEventId != null && beforeEventId <= 0) {
      throw new IllegalArgumentException("cursor must be a positive event id");
    }
    if (limit < 1 || limit > 50) {
      throw new IllegalArgumentException("limit must be between 1 and 50");
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireManager(connection, actorUserId, workspaceId);
        String sql =
            beforeEventId == null
                ? """
                  SELECT event_id, workspace_id, actor_user_id, subject_user_id,
                         action_code, reason_code, previous_role, new_role,
                         previous_status, new_status, occurred_at
                  FROM workspace_membership_audit_events
                  WHERE workspace_id = ?
                  ORDER BY event_id DESC
                  LIMIT ?
                  """
                : """
                  SELECT event_id, workspace_id, actor_user_id, subject_user_id,
                         action_code, reason_code, previous_role, new_role,
                         previous_status, new_status, occurred_at
                  FROM workspace_membership_audit_events
                  WHERE workspace_id = ? AND event_id < ?
                  ORDER BY event_id DESC
                  LIMIT ?
                  """;
        try (PreparedStatement select = connection.prepareStatement(sql)) {
          select.setObject(1, workspaceId);
          int limitIndex = 2;
          if (beforeEventId != null) {
            select.setLong(2, beforeEventId);
            limitIndex = 3;
          }
          select.setInt(limitIndex, limit + 1);
          List<Event> events = new ArrayList<>();
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) events.add(readEvent(rows));
          }
          boolean hasMore = events.size() > limit;
          if (hasMore) events.removeLast();
          String nextCursor = hasMore ? Long.toString(events.getLast().eventId()) : null;
          connection.commit();
          return new Page(List.copyOf(events), nextCursor);
        }
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkspaceRepository.WorkspaceRepositoryException
        | IllegalArgumentException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load workspace membership audit", exception);
    }
  }

  public static void append(
      Connection connection,
      UUID workspaceId,
      UUID actorUserId,
      UUID subjectUserId,
      Action action,
      Reason reason,
      WorkspaceRepository.Role previousRole,
      WorkspaceRepository.Role newRole,
      WorkspaceRepository.MemberStatus previousStatus,
      WorkspaceRepository.MemberStatus newStatus)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO workspace_membership_audit_events (
                workspace_id, actor_user_id, subject_user_id, action_code, reason_code,
                previous_role, new_role, previous_status, new_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      insert.setObject(1, workspaceId);
      insert.setObject(2, actorUserId);
      insert.setObject(3, subjectUserId);
      insert.setString(4, action.name());
      insert.setString(5, reason.name());
      insert.setString(6, previousRole == null ? null : previousRole.value());
      insert.setString(7, newRole == null ? null : newRole.value());
      insert.setString(
          8, previousStatus == null ? null : previousStatus.name().toLowerCase(Locale.ROOT));
      insert.setString(9, newStatus == null ? null : newStatus.name().toLowerCase(Locale.ROOT));
      insert.executeUpdate();
    }
  }

  private static void requireManager(Connection connection, UUID actorUserId, UUID workspaceId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT member.role
            FROM workspaces workspace
            JOIN workspace_members member ON member.workspace_id = workspace.id
            WHERE workspace.id = ? AND member.user_id = ? AND member.status = 'active'
            FOR UPDATE OF workspace, member
            """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new WorkspaceRepository.WorkspaceAccessDeniedException();
        String role = row.getString("role");
        if (!"owner".equals(role) && !"admin".equals(role)) {
          throw new WorkspaceRepository.WorkspaceAccessDeniedException();
        }
      }
    }
  }

  private static Event readEvent(ResultSet row) throws SQLException {
    return new Event(
        row.getLong("event_id"),
        row.getObject("workspace_id", UUID.class),
        row.getObject("actor_user_id", UUID.class),
        row.getObject("subject_user_id", UUID.class),
        Action.valueOf(row.getString("action_code")),
        Reason.valueOf(row.getString("reason_code")),
        role(row.getString("previous_role")),
        role(row.getString("new_role")),
        status(row.getString("previous_status")),
        status(row.getString("new_status")),
        row.getObject("occurred_at", OffsetDateTime.class).toInstant());
  }

  private static WorkspaceRepository.Role role(String value) {
    return value == null ? null : WorkspaceRepository.Role.valueOf(value.toUpperCase(Locale.ROOT));
  }

  private static WorkspaceRepository.MemberStatus status(String value) {
    return value == null
        ? null
        : WorkspaceRepository.MemberStatus.valueOf(value.toUpperCase(Locale.ROOT));
  }

  public enum Action {
    WORKSPACE_CREATED,
    MEMBER_GRANTED,
    MEMBER_ROLE_CHANGED,
    MEMBER_REVOKED,
    MEMBER_REACTIVATED,
    OWNERSHIP_TRANSFERRED,
    INVITATION_ACCEPTED,
    MEMBERSHIP_BASELINE
  }

  public enum Reason {
    INITIAL_OWNER,
    MANAGER_REQUEST,
    OWNER_HANDOVER,
    INVITATION_ACCEPTED,
    PRE_AUDIT_BASELINE
  }

  public record Event(
      long eventId,
      UUID workspaceId,
      UUID actorUserId,
      UUID subjectUserId,
      Action action,
      Reason reason,
      WorkspaceRepository.Role previousRole,
      WorkspaceRepository.Role newRole,
      WorkspaceRepository.MemberStatus previousStatus,
      WorkspaceRepository.MemberStatus newStatus,
      java.time.Instant occurredAt) {}

  public record Page(List<Event> items, String nextCursor) {}
}
