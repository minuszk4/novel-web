package com.novelweb.identity.roles;

import java.security.MessageDigest;
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

/** Stores current role assignments and immutable grant/revocation audit events. */
@Repository
@Profile("postgres")
public class SecurityRoleAssignmentRepository {

  private final DataSource dataSource;

  public SecurityRoleAssignmentRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public List<ReviewerAssignment> listActiveReviewers(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveAdmin(connection, actorUserId);
        List<ReviewerAssignment> assignments = new ArrayList<>();
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT assignment.id, assignment.user_id, target.display_name,
                           CASE WHEN target.email_verified_at IS NOT NULL THEN target.contact_email END AS contact_email,
                           target.status, assignment.assigned_by_user_id, assignment.assigned_at,
                           assignment.reason_code, assignment.revoked_at,
                           assignment.revoked_by_user_id, assignment.revocation_reason_code
                    FROM security_role_assignments assignment
                    JOIN users target ON target.id = assignment.user_id
                    WHERE assignment.role_code = 'publication_reviewer'
                      AND assignment.revoked_at IS NULL
                    ORDER BY assignment.assigned_at DESC, assignment.id DESC
                    """)) {
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) assignments.add(readAssignment(rows));
          }
        }
        connection.commit();
        return List.copyOf(assignments);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (NotSystemAdminException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list publication reviewer assignments", exception);
    }
  }

  public CommandResult grantReviewer(
      UUID actorUserId,
      UUID targetUserId,
      String reasonCode,
      byte[] idempotencyKeyHash,
      byte[] requestHash) {
    return executeCommand(
        actorUserId, targetUserId, reasonCode, idempotencyKeyHash, requestHash, "GRANT");
  }

  public CommandResult revokeReviewer(
      UUID actorUserId,
      UUID targetUserId,
      String reasonCode,
      byte[] idempotencyKeyHash,
      byte[] requestHash) {
    return executeCommand(
        actorUserId, targetUserId, reasonCode, idempotencyKeyHash, requestHash, "REVOKE");
  }

  private CommandResult executeCommand(
      UUID actorUserId,
      UUID targetUserId,
      String reasonCode,
      byte[] idempotencyKeyHash,
      byte[] requestHash,
      String action) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(targetUserId, "target user id is required");
    Objects.requireNonNull(reasonCode, "reason code is required");
    Objects.requireNonNull(idempotencyKeyHash, "idempotency key hash is required");
    Objects.requireNonNull(requestHash, "request hash is required");
    if (idempotencyKeyHash.length != 32 || requestHash.length != 32) {
      throw new IllegalArgumentException("role command hashes must be SHA-256");
    }

    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        requireActiveAdmin(connection, actorUserId);
        lockIdempotencyKey(connection, actorUserId, idempotencyKeyHash);
        Optional<Receipt> prior =
            findReceipt(connection, actorUserId, idempotencyKeyHash, requestHash);
        if (prior.isPresent()) {
          Receipt receipt = prior.orElseThrow();
          Optional<ReviewerAssignment> assignment =
              receipt.assignmentId() == null
                  ? Optional.empty()
                  : findAssignmentById(connection, receipt.assignmentId());
          if (receipt.assignmentId() != null && assignment.isEmpty()) {
            throw new TargetUserUnavailableException();
          }
          connection.commit();
          return new CommandResult(
              receipt.resultCode(), receipt.responseStatus(), assignment.orElse(null), true);
        }

        if ("GRANT".equals(action) && actorUserId.equals(targetUserId)) {
          throw new SelfAssignmentNotAllowedException();
        }
        lockTargetUser(connection, targetUserId, "GRANT".equals(action));
        Optional<AssignmentRow> active = lockActiveAssignment(connection, targetUserId);
        CommandResult result;
        if ("GRANT".equals(action)) {
          if (active.isPresent()) {
            ReviewerAssignment assignment =
                findAssignmentById(connection, active.orElseThrow().id()).orElseThrow();
            result = new CommandResult("ALREADY_ACTIVE", 200, assignment, false);
          } else {
            UUID assignmentId = UUID.randomUUID();
            insertAssignment(connection, assignmentId, actorUserId, targetUserId, reasonCode);
            insertEvent(connection, assignmentId, targetUserId, actorUserId, "GRANTED", reasonCode);
            ReviewerAssignment assignment =
                findAssignmentById(connection, assignmentId).orElseThrow();
            result = new CommandResult("GRANTED", 201, assignment, false);
          }
        } else if (active.isPresent()) {
          UUID assignmentId = active.orElseThrow().id();
          revokeAssignment(connection, assignmentId, actorUserId, reasonCode);
          insertEvent(connection, assignmentId, targetUserId, actorUserId, "REVOKED", reasonCode);
          ReviewerAssignment assignment =
              findAssignmentById(connection, assignmentId).orElseThrow();
          result = new CommandResult("REVOKED", 204, assignment, false);
        } else {
          result = new CommandResult("ALREADY_REVOKED", 204, null, false);
        }

        insertReceipt(
            connection,
            actorUserId,
            idempotencyKeyHash,
            requestHash,
            result.assignment() == null ? null : result.assignment().assignmentId(),
            result.resultCode(),
            result.responseStatus());
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (NotSystemAdminException
        | TargetUserUnavailableException
        | SelfAssignmentNotAllowedException
        | IdempotencyKeyReusedException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to update publication reviewer assignments", exception);
    }
  }

  private static void requireActiveAdmin(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT assignment.id
                FROM security_role_assignments assignment
                JOIN users actor ON actor.id = assignment.user_id
                WHERE assignment.user_id = ?
                  AND assignment.role_code = 'system_admin'
                  AND assignment.revoked_at IS NULL
                  AND actor.status = 'active'
                FOR SHARE OF assignment, actor
                """)) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new NotSystemAdminException();
      }
    }
  }

  private static void lockIdempotencyKey(
      Connection connection, UUID actorUserId, byte[] idempotencyKeyHash) throws SQLException {
    String lockKey = SecurityRoleAssignmentService.advisoryLockKey(actorUserId, idempotencyKeyHash);
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      lock.setString(1, lockKey);
      lock.executeQuery().close();
    }
  }

  private static Optional<Receipt> findReceipt(
      Connection connection, UUID actorUserId, byte[] idempotencyKeyHash, byte[] requestHash)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request_sha256, assignment_id, result_code, response_status
                FROM security_role_assignment_idempotency
                WHERE actor_user_id = ? AND idempotency_key_sha256 = ?
                FOR UPDATE
                """)) {
      select.setObject(1, actorUserId);
      select.setBytes(2, idempotencyKeyHash);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        if (!MessageDigest.isEqual(requestHash, row.getBytes("request_sha256"))) {
          throw new IdempotencyKeyReusedException();
        }
        return Optional.of(
            new Receipt(
                row.getObject("assignment_id", UUID.class),
                row.getString("result_code"),
                row.getInt("response_status")));
      }
    }
  }

  private static void lockTargetUser(Connection connection, UUID targetUserId, boolean mustBeActive)
      throws SQLException {
    String sql = "SELECT id FROM users WHERE id = ?";
    if (mustBeActive) sql += " AND status = 'active'";
    sql += " FOR UPDATE";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, targetUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new TargetUserUnavailableException();
      }
    }
  }

  private static Optional<AssignmentRow> lockActiveAssignment(
      Connection connection, UUID targetUserId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id FROM security_role_assignments
                WHERE user_id = ? AND role_code = 'publication_reviewer' AND revoked_at IS NULL
                FOR UPDATE
                """)) {
      select.setObject(1, targetUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(new AssignmentRow(row.getObject("id", UUID.class)))
            : Optional.empty();
      }
    }
  }

  private static void insertAssignment(
      Connection connection,
      UUID assignmentId,
      UUID actorUserId,
      UUID targetUserId,
      String reasonCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO security_role_assignments
                    (id, user_id, role_code, assigned_by_user_id, reason_code)
                VALUES (?, ?, 'publication_reviewer', ?, ?)
                """)) {
      insert.setObject(1, assignmentId);
      insert.setObject(2, targetUserId);
      insert.setObject(3, actorUserId);
      insert.setString(4, reasonCode);
      insert.executeUpdate();
    }
  }

  private static void revokeAssignment(
      Connection connection, UUID assignmentId, UUID actorUserId, String reasonCode)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE security_role_assignments
                SET revoked_by_user_id = ?, revoked_at = clock_timestamp(),
                    revocation_reason_code = ?
                WHERE id = ? AND revoked_at IS NULL
                """)) {
      update.setObject(1, actorUserId);
      update.setString(2, reasonCode);
      update.setObject(3, assignmentId);
      if (update.executeUpdate() != 1)
        throw new SQLException("Role assignment changed unexpectedly");
    }
  }

  private static void insertEvent(
      Connection connection,
      UUID assignmentId,
      UUID targetUserId,
      UUID actorUserId,
      String action,
      String reasonCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO security_role_assignment_events
                    (id, assignment_id, target_user_id, role_code, action_code,
                     actor_user_id, reason_code)
                VALUES (?, ?, ?, 'publication_reviewer', ?, ?, ?)
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, assignmentId);
      insert.setObject(3, targetUserId);
      insert.setString(4, action);
      insert.setObject(5, actorUserId);
      insert.setString(6, reasonCode);
      insert.executeUpdate();
    }
  }

  private static void insertReceipt(
      Connection connection,
      UUID actorUserId,
      byte[] idempotencyKeyHash,
      byte[] requestHash,
      UUID assignmentId,
      String resultCode,
      int responseStatus)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO security_role_assignment_idempotency
                    (actor_user_id, idempotency_key_sha256, request_sha256, assignment_id,
                     result_code, response_status)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, actorUserId);
      insert.setBytes(2, idempotencyKeyHash);
      insert.setBytes(3, requestHash);
      insert.setObject(4, assignmentId);
      insert.setString(5, resultCode);
      insert.setInt(6, responseStatus);
      insert.executeUpdate();
    }
  }

  private static Optional<ReviewerAssignment> findAssignmentById(Connection connection, UUID id)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT assignment.id, assignment.user_id, target.display_name,
                       CASE WHEN target.email_verified_at IS NOT NULL THEN target.contact_email END AS contact_email,
                       target.status, assignment.assigned_by_user_id, assignment.assigned_at,
                       assignment.reason_code, assignment.revoked_at,
                       assignment.revoked_by_user_id, assignment.revocation_reason_code
                FROM security_role_assignments assignment
                JOIN users target ON target.id = assignment.user_id
                WHERE assignment.id = ?
                """)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readAssignment(row)) : Optional.empty();
      }
    }
  }

  private static ReviewerAssignment readAssignment(ResultSet row) throws SQLException {
    return new ReviewerAssignment(
        row.getObject("id", UUID.class),
        row.getObject("user_id", UUID.class),
        row.getString("display_name"),
        row.getString("contact_email"),
        row.getString("status"),
        row.getObject("assigned_by_user_id", UUID.class),
        row.getObject("assigned_at", OffsetDateTime.class),
        row.getString("reason_code"),
        row.getObject("revoked_at", OffsetDateTime.class),
        row.getObject("revoked_by_user_id", UUID.class),
        row.getString("revocation_reason_code"));
  }

  private static void rollback(Connection connection, Exception originalException) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      originalException.addSuppressed(rollbackException);
    }
  }

  private record AssignmentRow(UUID id) {}

  private record Receipt(UUID assignmentId, String resultCode, int responseStatus) {}

  public record ReviewerAssignment(
      UUID assignmentId,
      UUID userId,
      String displayName,
      String contactEmail,
      String userStatus,
      UUID assignedByUserId,
      OffsetDateTime assignedAt,
      String reasonCode,
      OffsetDateTime revokedAt,
      UUID revokedByUserId,
      String revocationReasonCode) {
    public boolean active() {
      return revokedAt == null;
    }
  }

  public record CommandResult(
      String resultCode, int responseStatus, ReviewerAssignment assignment, boolean replayed) {}

  public static final class NotSystemAdminException extends RuntimeException {}

  public static final class TargetUserUnavailableException extends RuntimeException {}

  public static final class SelfAssignmentNotAllowedException extends RuntimeException {}

  public static final class IdempotencyKeyReusedException extends RuntimeException {}
}
