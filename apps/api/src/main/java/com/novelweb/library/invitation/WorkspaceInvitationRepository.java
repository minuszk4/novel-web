package com.novelweb.library.invitation;

import com.novelweb.library.WorkspaceMembershipAuditRepository;
import com.novelweb.library.WorkspaceRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** PostgreSQL transactions for workspace invitations and their privacy-safe mail outbox. */
@Repository
@Profile("postgres")
public class WorkspaceInvitationRepository {

  private static final int MAX_ATTEMPTS = 8;
  private static final int BATCH_SIZE = 10;
  private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

  private final DataSource dataSource;

  public WorkspaceInvitationRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  public InvitationRecord create(
      UUID actorId, UUID workspaceId, UUID invitationId, String email, String role, Duration ttl) {
    return transaction(
        "Unable to create a workspace invitation",
        connection -> {
          requireManager(connection, actorId, workspaceId);
          InvitationRecord replay = findByIdForUpdate(connection, invitationId);
          if (replay != null) {
            if (replay.workspaceId().equals(workspaceId)
                && replay.invitedByUserId().equals(actorId)
                && replay.invitedEmail().equals(email)
                && replay.role().equals(role)) return replay;
            throw new InvitationConflictException();
          }
          lockQuotaScopes(connection, actorId, email);
          expireMatchingInvitation(connection, workspaceId, email);
          enforceRateLimits(connection, actorId, workspaceId, email);
          try (PreparedStatement insert =
              connection.prepareStatement(
                  """
                  INSERT INTO workspace_invitations
                      (id, workspace_id, invited_email, requested_role, invited_by_user_id,
                       sender_consent_at, expires_at)
                  VALUES (?, ?, ?, ?, ?, clock_timestamp(), clock_timestamp() + (? * interval '1 millisecond'))
                  RETURNING id, workspace_id, invited_email, requested_role,
                            invited_by_user_id, status, created_at, expires_at
                  """)) {
            insert.setObject(1, invitationId);
            insert.setObject(2, workspaceId);
            insert.setString(3, email);
            insert.setString(4, role);
            insert.setObject(5, actorId);
            insert.setLong(6, ttl.toMillis());
            try (ResultSet row = insert.executeQuery()) {
              row.next();
              InvitationRecord record = invitation(row);
              try (PreparedStatement outbox =
                  connection.prepareStatement(
                      "INSERT INTO workspace_invitation_outbox (id, invitation_id) VALUES (?, ?)")) {
                outbox.setObject(1, UUID.randomUUID());
                outbox.setObject(2, invitationId);
                outbox.executeUpdate();
              }
              return record;
            }
          }
        });
  }

  public List<InvitationSummary> list(UUID actorId, UUID workspaceId) {
    try (Connection connection = dataSource.getConnection()) {
      requireManager(connection, actorId, workspaceId);
      try (PreparedStatement select =
          connection.prepareStatement(
              """
              SELECT id, requested_role, status, created_at, expires_at
                FROM workspace_invitations
               WHERE workspace_id = ? AND status = 'pending'
               ORDER BY created_at DESC, id DESC
               LIMIT 100
              """)) {
        select.setObject(1, workspaceId);
        try (ResultSet rows = select.executeQuery()) {
          List<InvitationSummary> result = new ArrayList<>();
          while (rows.next()) {
            result.add(
                new InvitationSummary(
                    rows.getObject("id", UUID.class),
                    rows.getString("requested_role"),
                    rows.getString("status"),
                    rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rows.getObject("expires_at", OffsetDateTime.class).toInstant()));
          }
          return List.copyOf(result);
        }
      }
    } catch (WorkspaceInvitationException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list workspace invitations", exception);
    }
  }

  public void revoke(UUID actorId, UUID workspaceId, UUID invitationId) {
    transaction(
        "Unable to revoke a workspace invitation",
        connection -> {
          requireManager(connection, actorId, workspaceId);
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                  UPDATE workspace_invitations
                     SET status = 'revoked', revoked_at = clock_timestamp()
                   WHERE id = ? AND workspace_id = ? AND status = 'pending'
                     AND expires_at > clock_timestamp()
                  """)) {
            update.setObject(1, invitationId);
            update.setObject(2, workspaceId);
            if (update.executeUpdate() != 1) throw new InvitationNotFoundException();
          }
          cancelOutbox(connection, invitationId);
          return null;
        });
  }

  public Optional<RecipientInvitation> findForRecipient(UUID actorId, UUID invitationId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT invitation.id, invitation.workspace_id, invitation.invited_email,
                       invitation.requested_role, invitation.expires_at,
                       workspace.name AS workspace_name
                  FROM workspace_invitations invitation
                  JOIN workspaces workspace ON workspace.id = invitation.workspace_id
                  JOIN users recipient ON recipient.id = ? AND recipient.status = 'active'
                   AND recipient.email_verified_at IS NOT NULL
                   AND lower(recipient.contact_email) = invitation.invited_email
                 WHERE invitation.id = ? AND invitation.status = 'pending'
                   AND invitation.expires_at > clock_timestamp()
                """)) {
      select.setObject(1, actorId);
      select.setObject(2, invitationId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new RecipientInvitation(
                row.getObject("id", UUID.class),
                row.getObject("workspace_id", UUID.class),
                row.getString("workspace_name"),
                row.getString("invited_email"),
                row.getString("requested_role"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant()));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load a workspace invitation", exception);
    }
  }

  public void decide(UUID actorId, UUID invitationId, boolean accept) {
    transaction(
        "Unable to respond to a workspace invitation",
        connection -> {
          UUID workspaceId = findInvitationWorkspace(connection, invitationId);
          lockWorkspace(connection, workspaceId);
          String email = verifiedEmail(connection, actorId);
          String role;
          try (PreparedStatement select =
              connection.prepareStatement(
                  """
                  SELECT workspace_id, invited_email, requested_role
                    FROM workspace_invitations
                   WHERE id = ? AND status = 'pending' AND expires_at > clock_timestamp()
                   FOR UPDATE
                  """)) {
            select.setObject(1, invitationId);
            try (ResultSet row = select.executeQuery()) {
              if (!row.next() || !email.equals(row.getString("invited_email"))) {
                throw new InvitationNotFoundException();
              }
              if (!workspaceId.equals(row.getObject("workspace_id", UUID.class))) {
                throw new InvitationNotFoundException();
              }
              role = row.getString("requested_role");
            }
          }
          if (accept) acceptMembership(connection, actorId, workspaceId, role);
          try (PreparedStatement update =
              connection.prepareStatement(
                  accept
                      ? """
                        UPDATE workspace_invitations
                           SET status = 'accepted', accepted_by_user_id = ?, accepted_at = clock_timestamp()
                         WHERE id = ? AND status = 'pending'
                        """
                      : """
                        UPDATE workspace_invitations
                           SET status = 'rejected', rejected_at = clock_timestamp()
                         WHERE id = ? AND status = 'pending'
                        """)) {
            if (accept) {
              update.setObject(1, actorId);
              update.setObject(2, invitationId);
            } else {
              update.setObject(1, invitationId);
            }
            if (update.executeUpdate() != 1) throw new InvitationNotFoundException();
          }
          cancelOutbox(connection, invitationId);
          return null;
        });
  }

  public List<ClaimedDelivery> claimDue() {
    List<ClaimedDelivery> claimed = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        expireDue(connection);
        try (PreparedStatement failExhausted =
            connection.prepareStatement(
                """
                UPDATE workspace_invitation_outbox
                   SET status = 'failed', lease_token = NULL, lease_expires_at = NULL,
                       last_safe_failure_code = 'RETRY_EXHAUSTED'
                 WHERE status = 'leased' AND attempts >= ? AND lease_expires_at <= clock_timestamp()
                """)) {
          failExhausted.setInt(1, MAX_ATTEMPTS);
          failExhausted.executeUpdate();
        }
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT outbox.id, outbox.invitation_id, outbox.attempts + 1 AS attempt
                  FROM workspace_invitation_outbox outbox
                  JOIN workspace_invitations invitation ON invitation.id = outbox.invitation_id
                 WHERE invitation.status = 'pending' AND invitation.expires_at > clock_timestamp()
                   AND outbox.attempts < ?
                   AND ((outbox.status IN ('queued', 'retry') AND outbox.next_attempt_at <= clock_timestamp())
                     OR (outbox.status = 'leased' AND outbox.lease_expires_at <= clock_timestamp()))
                 ORDER BY outbox.next_attempt_at, outbox.created_at, outbox.id
                 LIMIT ? FOR UPDATE OF outbox SKIP LOCKED
                """)) {
          select.setInt(1, MAX_ATTEMPTS);
          select.setInt(2, BATCH_SIZE);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              UUID outboxId = rows.getObject("id", UUID.class);
              UUID invitationId = rows.getObject("invitation_id", UUID.class);
              UUID lease = UUID.randomUUID();
              int attempts = rows.getInt("attempt");
              try (PreparedStatement update =
                  connection.prepareStatement(
                      """
                      UPDATE workspace_invitation_outbox
                         SET status = 'leased', attempts = ?, lease_token = ?,
                             lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                             last_safe_failure_code = CASE WHEN status = 'leased' THEN 'LEASE_EXPIRED'
                                                           ELSE last_safe_failure_code END
                       WHERE id = ?
                      """)) {
                update.setInt(1, attempts);
                update.setObject(2, lease);
                update.setLong(3, LEASE_DURATION.toMillis());
                update.setObject(4, outboxId);
                if (update.executeUpdate() != 1)
                  throw new SQLException("Invitation lease was lost");
              }
              claimed.add(new ClaimedDelivery(outboxId, invitationId, lease, attempts));
            }
          }
        }
        connection.commit();
        return List.copyOf(claimed);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim workspace invitation delivery", exception);
    }
  }

  public Optional<WorkspaceInvitationMailer.Delivery> delivery(ClaimedDelivery claim) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT invitation.invited_email, invitation.requested_role, workspace.name
                  FROM workspace_invitation_outbox outbox
                  JOIN workspace_invitations invitation ON invitation.id = outbox.invitation_id
                  JOIN workspaces workspace ON workspace.id = invitation.workspace_id
                 WHERE outbox.id = ? AND outbox.invitation_id = ?
                   AND outbox.status = 'leased' AND outbox.lease_token = ?
                   AND outbox.lease_expires_at > clock_timestamp()
                   AND invitation.status = 'pending' AND invitation.expires_at > clock_timestamp()
                """)) {
      select.setObject(1, claim.outboxId());
      select.setObject(2, claim.invitationId());
      select.setObject(3, claim.leaseToken());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new WorkspaceInvitationMailer.Delivery(
                claim.invitationId(),
                row.getString("invited_email"),
                row.getString("name"),
                row.getString("requested_role")));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load workspace invitation delivery", exception);
    }
  }

  public void complete(ClaimedDelivery claim) {
    updateDelivery(
        claim,
        """
        UPDATE workspace_invitation_outbox
           SET status = 'sent', sent_at = clock_timestamp(), lease_token = NULL,
               lease_expires_at = NULL, last_safe_failure_code = NULL
         WHERE id = ? AND status = 'leased' AND lease_token = ?
        """,
        null);
  }

  public void cancelClaim(ClaimedDelivery claim) {
    updateDelivery(
        claim,
        """
        UPDATE workspace_invitation_outbox
           SET status = 'cancelled', lease_token = NULL, lease_expires_at = NULL,
               last_safe_failure_code = 'INVITATION_NOT_PENDING'
         WHERE id = ? AND status = 'leased' AND lease_token = ?
        """,
        null);
  }

  public void fail(ClaimedDelivery claim, boolean retryable) {
    long delayMillis = Math.min(900_000L, 2_000L << Math.min(claim.attempt() - 1, 9));
    String sql =
        retryable && claim.attempt() < MAX_ATTEMPTS
            ? """
          UPDATE workspace_invitation_outbox
             SET status = 'retry', next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                 lease_token = NULL, lease_expires_at = NULL, last_safe_failure_code = 'SMTP_UNAVAILABLE'
           WHERE id = ? AND status = 'leased' AND lease_token = ?
          """
            : """
          UPDATE workspace_invitation_outbox
             SET status = 'failed', lease_token = NULL, lease_expires_at = NULL,
                 last_safe_failure_code = 'RETRY_EXHAUSTED'
           WHERE id = ? AND status = 'leased' AND lease_token = ?
          """;
    updateDelivery(claim, sql, retryable && claim.attempt() < MAX_ATTEMPTS ? delayMillis : null);
  }

  private static void acceptMembership(
      Connection connection, UUID userId, UUID workspaceId, String role) throws SQLException {
    try (PreparedStatement workspaceLock =
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR UPDATE")) {
      workspaceLock.setObject(1, workspaceId);
      try (ResultSet row = workspaceLock.executeQuery()) {
        if (!row.next()) throw new InvitationNotFoundException();
      }
    }
    try (PreparedStatement member =
        connection.prepareStatement(
            "SELECT role, status FROM workspace_members WHERE workspace_id = ? AND user_id = ? FOR UPDATE")) {
      member.setObject(1, workspaceId);
      member.setObject(2, userId);
      try (ResultSet row = member.executeQuery()) {
        if (row.next()) {
          if ("active".equals(row.getString("status"))) return;
          WorkspaceRepository.Role previousRole =
              WorkspaceRepository.Role.valueOf(row.getString("role").toUpperCase(Locale.ROOT));
          try (PreparedStatement reactivate =
              connection.prepareStatement(
                  """
                  UPDATE workspace_members
                     SET role = ?, status = 'active', joined_at = clock_timestamp(),
                         revoked_at = NULL, updated_at = clock_timestamp()
                   WHERE workspace_id = ? AND user_id = ? AND status = 'revoked'
                  """)) {
            reactivate.setString(1, role);
            reactivate.setObject(2, workspaceId);
            reactivate.setObject(3, userId);
            if (reactivate.executeUpdate() != 1) throw new InvitationConflictException();
          }
          bumpScopeVersion(connection, workspaceId);
          WorkspaceMembershipAuditRepository.append(
              connection,
              workspaceId,
              userId,
              userId,
              WorkspaceMembershipAuditRepository.Action.INVITATION_ACCEPTED,
              WorkspaceMembershipAuditRepository.Reason.INVITATION_ACCEPTED,
              previousRole,
              WorkspaceRepository.Role.valueOf(role.toUpperCase(Locale.ROOT)),
              WorkspaceRepository.MemberStatus.REVOKED,
              WorkspaceRepository.MemberStatus.ACTIVE);
          return;
        }
      }
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)")) {
      insert.setObject(1, workspaceId);
      insert.setObject(2, userId);
      insert.setString(3, role);
      insert.executeUpdate();
    }
    bumpScopeVersion(connection, workspaceId);
    WorkspaceMembershipAuditRepository.append(
        connection,
        workspaceId,
        userId,
        userId,
        WorkspaceMembershipAuditRepository.Action.INVITATION_ACCEPTED,
        WorkspaceMembershipAuditRepository.Reason.INVITATION_ACCEPTED,
        null,
        WorkspaceRepository.Role.valueOf(role.toUpperCase(Locale.ROOT)),
        null,
        WorkspaceRepository.MemberStatus.ACTIVE);
  }

  private static void expireMatchingInvitation(
      Connection connection, UUID workspaceId, String email) throws SQLException {
    UUID expiredId = null;
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE workspace_invitations
               SET status = 'expired'
             WHERE workspace_id = ? AND invited_email = ? AND status = 'pending'
               AND expires_at <= clock_timestamp()
            RETURNING id
            """)) {
      update.setObject(1, workspaceId);
      update.setString(2, email);
      try (ResultSet rows = update.executeQuery()) {
        if (rows.next()) expiredId = rows.getObject(1, UUID.class);
      }
    }
    if (expiredId != null) cancelOutbox(connection, expiredId);
  }

  private static void enforceRateLimits(
      Connection connection, UUID actorId, UUID workspaceId, String email) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT count(*) FILTER (WHERE invited_by_user_id = ?) AS actor_count,
                   count(*) FILTER (WHERE workspace_id = ?) AS workspace_count,
                   count(*) FILTER (WHERE invited_email = ?) AS email_count
              FROM workspace_invitations
             WHERE created_at >= clock_timestamp() - interval '24 hours'
            """)) {
      select.setObject(1, actorId);
      select.setObject(2, workspaceId);
      select.setString(3, email);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()
            || row.getLong("actor_count") >= 10
            || row.getLong("workspace_count") >= 30
            || row.getLong("email_count") >= 3) {
          throw new InvitationRateLimitException();
        }
      }
    }
  }

  private static void lockQuotaScopes(Connection connection, UUID actorId, String email)
      throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      lock.setString(1, "workspace-invitation:actor:" + actorId);
      lock.execute();
      lock.setString(1, "workspace-invitation:email:" + email);
      lock.execute();
    }
  }

  private static UUID findInvitationWorkspace(Connection connection, UUID invitationId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT workspace_id FROM workspace_invitations WHERE id = ? AND status = 'pending'")) {
      select.setObject(1, invitationId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new InvitationNotFoundException();
        return row.getObject("workspace_id", UUID.class);
      }
    }
  }

  private static void lockWorkspace(Connection connection, UUID workspaceId) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR UPDATE")) {
      lock.setObject(1, workspaceId);
      try (ResultSet row = lock.executeQuery()) {
        if (!row.next()) throw new InvitationNotFoundException();
      }
    }
  }

  private static void expireDue(Connection connection) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            WITH expired AS (
                UPDATE workspace_invitations
                   SET status = 'expired'
                 WHERE id IN (
                     SELECT id FROM workspace_invitations
                      WHERE status = 'pending' AND expires_at <= clock_timestamp()
                      ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                 )
                RETURNING id
            )
            UPDATE workspace_invitation_outbox outbox
               SET status = 'cancelled', lease_token = NULL, lease_expires_at = NULL,
                   last_safe_failure_code = 'INVITATION_NOT_PENDING'
              FROM expired WHERE outbox.invitation_id = expired.id
                AND outbox.status IN ('queued', 'retry', 'leased')
            """)) {
      update.setInt(1, BATCH_SIZE);
      update.executeUpdate();
    }
  }

  private static void cancelOutbox(Connection connection, UUID invitationId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE workspace_invitation_outbox
               SET status = 'cancelled', lease_token = NULL, lease_expires_at = NULL,
                   last_safe_failure_code = 'INVITATION_NOT_PENDING'
             WHERE invitation_id = ? AND status IN ('queued', 'retry', 'leased')
            """)) {
      update.setObject(1, invitationId);
      update.executeUpdate();
    }
  }

  private static String verifiedEmail(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT lower(contact_email) FROM users WHERE id = ? AND status = 'active' AND email_verified_at IS NOT NULL FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new InvitationNotFoundException();
        return row.getString(1);
      }
    }
  }

  private static InvitationRecord findByIdForUpdate(Connection connection, UUID id)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT id, workspace_id, invited_email, requested_role, invited_by_user_id,
                   status, created_at, expires_at
              FROM workspace_invitations WHERE id = ? FOR UPDATE
            """)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? invitation(row) : null;
      }
    }
  }

  private static InvitationRecord invitation(ResultSet row) throws SQLException {
    return new InvitationRecord(
        row.getObject("id", UUID.class),
        row.getObject("workspace_id", UUID.class),
        row.getString("invited_email"),
        row.getString("requested_role"),
        row.getObject("invited_by_user_id", UUID.class),
        row.getString("status"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant());
  }

  private static void requireManager(Connection connection, UUID actorId, UUID workspaceId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT member.role FROM workspaces workspace
            JOIN workspace_members member ON member.workspace_id = workspace.id
             WHERE workspace.id = ? AND member.user_id = ? AND member.status = 'active'
             FOR UPDATE OF workspace, member
            """)) {
      select.setObject(1, workspaceId);
      select.setObject(2, actorId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()
            || !("owner".equals(row.getString(1)) || "admin".equals(row.getString(1)))) {
          throw new InvitationNotFoundException();
        }
      }
    }
  }

  private static void bumpScopeVersion(Connection connection, UUID workspaceId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE workspaces SET scope_version = scope_version + 1, updated_at = clock_timestamp() WHERE id = ?")) {
      update.setObject(1, workspaceId);
      if (update.executeUpdate() != 1) throw new InvitationNotFoundException();
    }
  }

  private void updateDelivery(ClaimedDelivery claim, String sql, Long delayMillis) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update = connection.prepareStatement(sql)) {
      int parameter = 1;
      if (delayMillis != null) update.setLong(parameter++, delayMillis);
      update.setObject(parameter++, claim.outboxId());
      update.setObject(parameter, claim.leaseToken());
      if (update.executeUpdate() != 1) throw new SQLException("Invitation outbox lease was lost");
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to update workspace invitation delivery", exception);
    }
  }

  private <T> T transaction(String failureMessage, SqlTransaction<T> action) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = action.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (WorkspaceInvitationException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new InvitationConflictException();
      throw new IllegalStateException(failureMessage, exception);
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      exception.addSuppressed(rollbackFailure);
    }
  }

  @FunctionalInterface
  private interface SqlTransaction<T> {
    T run(Connection connection) throws SQLException;
  }

  public record InvitationRecord(
      UUID id,
      UUID workspaceId,
      String invitedEmail,
      String role,
      UUID invitedByUserId,
      String status,
      java.time.Instant createdAt,
      java.time.Instant expiresAt) {}

  public record InvitationSummary(
      UUID id,
      String role,
      String status,
      java.time.Instant createdAt,
      java.time.Instant expiresAt) {}

  public record RecipientInvitation(
      UUID id,
      UUID workspaceId,
      String workspaceName,
      String invitedEmail,
      String role,
      java.time.Instant expiresAt) {}

  public record ClaimedDelivery(UUID outboxId, UUID invitationId, UUID leaseToken, int attempt) {}

  private static class WorkspaceInvitationException extends RuntimeException {}

  public static final class InvitationNotFoundException extends WorkspaceInvitationException {}

  public static final class InvitationConflictException extends WorkspaceInvitationException {}

  public static final class InvitationRateLimitException extends WorkspaceInvitationException {}
}
