package com.novelweb.library.collaboration;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** PostgreSQL transactions for private manuscript invitations and collaborator grants. */
@Repository
@Profile("postgres")
public class AuthoringCollaborationRepository {

  private static final int MAX_ATTEMPTS = 8;
  private static final int BATCH_SIZE = 10;
  private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

  private final DataSource dataSource;

  public AuthoringCollaborationRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  public InvitationRecord create(
      UUID actorId,
      UUID workId,
      UUID invitationId,
      String email,
      List<String> permissions,
      Duration ttl) {
    return transaction(
        "Unable to create a manuscript invitation",
        connection -> {
          requireOwnerWork(connection, actorId, workId, true);
          return createInTransaction(
              connection, actorId, workId, invitationId, email, permissions, ttl);
        });
  }

  public InvitationRecord createPermissionChange(
      UUID ownerId,
      UUID workId,
      UUID grantId,
      long expectedVersion,
      UUID invitationId,
      List<String> permissions,
      Duration ttl) {
    return transaction(
        "Unable to create a manuscript permission-change invitation",
        connection -> {
          requireOwnerWork(connection, ownerId, workId, true);
          String email;
          long currentVersion;
          boolean active;
          try (PreparedStatement select =
              connection.prepareStatement(
                  """
                  SELECT contributor.contributor_user_id, contributor.version, contributor.revoked_at,
                         lower(recipient.contact_email) AS contact_email
                    FROM authoring_manuscript_contributors contributor
                    JOIN users recipient ON recipient.id = contributor.contributor_user_id
                   WHERE contributor.id = ? AND contributor.work_id = ?
                     AND contributor.owner_user_id = ? AND recipient.status = 'active'
                     AND recipient.email_verified_at IS NOT NULL
                   FOR UPDATE OF contributor, recipient
                  """)) {
            select.setObject(1, grantId);
            select.setObject(2, workId);
            select.setObject(3, ownerId);
            try (ResultSet row = select.executeQuery()) {
              if (!row.next()) throw new InvitationNotFoundException();
              email = row.getString("contact_email");
              currentVersion = row.getLong("version");
              active = row.getObject("revoked_at") == null;
            }
          }
          InvitationRecord replay = findInvitationForUpdate(connection, invitationId);
          if (replay != null) {
            if (replay.workId().equals(workId)
                && replay.ownerUserId().equals(ownerId)
                && replay.invitedEmail().equals(email)
                && replay.permissions().equals(permissions)) return replay;
            throw new InvitationConflictException();
          }
          if (!active || currentVersion != expectedVersion) {
            throw new GrantPreconditionFailedException();
          }
          return createInTransaction(
              connection, ownerId, workId, invitationId, email, permissions, ttl);
        });
  }

  private InvitationRecord createInTransaction(
      Connection connection,
      UUID actorId,
      UUID workId,
      UUID invitationId,
      String email,
      List<String> permissions,
      Duration ttl)
      throws SQLException {
    InvitationRecord replay = findInvitationForUpdate(connection, invitationId);
    if (replay != null) {
      if (replay.workId().equals(workId)
          && replay.ownerUserId().equals(actorId)
          && replay.invitedEmail().equals(email)
          && replay.permissions().equals(permissions)) return replay;
      throw new InvitationConflictException();
    }
    lockQuotaScopes(connection, actorId, workId, email);
    expireMatching(connection, workId, email, actorId);
    enforceRateLimits(connection, actorId, workId, email);
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO authoring_manuscript_invitations
                (id, work_id, owner_user_id, invited_email, requested_permissions,
                 invited_by_user_id, sender_consent_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(),
                    clock_timestamp() + (? * interval '1 millisecond'))
            RETURNING id, work_id, owner_user_id, invited_email, requested_permissions,
                      status, accepted_by_user_id, created_at, expires_at
            """)) {
      insert.setObject(1, invitationId);
      insert.setObject(2, workId);
      insert.setObject(3, actorId);
      insert.setString(4, email);
      insert.setArray(5, textArray(connection, permissions));
      insert.setObject(6, actorId);
      insert.setLong(7, ttl.toMillis());
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        InvitationRecord record = invitation(row);
        try (PreparedStatement outbox =
            connection.prepareStatement(
                "INSERT INTO authoring_manuscript_invitation_outbox (id, invitation_id) VALUES (?, ?)")) {
          outbox.setObject(1, UUID.randomUUID());
          outbox.setObject(2, invitationId);
          outbox.executeUpdate();
        }
        insertInvitationEvent(connection, record, actorId, "INVITE_CREATED");
        return record;
      }
    }
  }

  public ManagementList list(UUID actorId, UUID workId) {
    return transaction(
        "Unable to list manuscript collaborators",
        connection -> {
          requireOwnerWork(connection, actorId, workId, false);
          expireWorkInvitations(connection, workId);
          List<GrantSummary> grants = new ArrayList<>();
          try (PreparedStatement select =
              connection.prepareStatement(
                  """
                  SELECT id, permissions, version, granted_at
                    FROM authoring_manuscript_contributors
                   WHERE work_id = ? AND owner_user_id = ? AND revoked_at IS NULL
                   ORDER BY granted_at DESC, id DESC LIMIT 100
                  """)) {
            select.setObject(1, workId);
            select.setObject(2, actorId);
            try (ResultSet rows = select.executeQuery()) {
              while (rows.next()) {
                grants.add(
                    new GrantSummary(
                        rows.getObject("id", UUID.class),
                        readTextArray(rows, "permissions"),
                        rows.getLong("version"),
                        instant(rows, "granted_at")));
              }
            }
          }
          List<InvitationSummary> invitations = new ArrayList<>();
          try (PreparedStatement select =
              connection.prepareStatement(
                  """
                  SELECT id, requested_permissions, status, created_at, expires_at
                    FROM authoring_manuscript_invitations
                   WHERE work_id = ? AND owner_user_id = ? AND status = 'pending'
                     AND expires_at > clock_timestamp()
                   ORDER BY created_at DESC, id DESC LIMIT 100
                  """)) {
            select.setObject(1, workId);
            select.setObject(2, actorId);
            try (ResultSet rows = select.executeQuery()) {
              while (rows.next()) {
                invitations.add(
                    new InvitationSummary(
                        rows.getObject("id", UUID.class),
                        readTextArray(rows, "requested_permissions"),
                        rows.getString("status"),
                        instant(rows, "created_at"),
                        instant(rows, "expires_at")));
              }
            }
          }
          return new ManagementList(List.copyOf(grants), List.copyOf(invitations));
        });
  }

  public void revokeInvitation(UUID actorId, UUID workId, UUID invitationId) {
    transaction(
        "Unable to revoke a manuscript invitation",
        connection -> {
          requireOwnerWork(connection, actorId, workId, true);
          InvitationRecord invitation = findInvitationForUpdate(connection, invitationId);
          if (invitation == null
              || !invitation.workId().equals(workId)
              || !invitation.ownerUserId().equals(actorId)) {
            throw new InvitationNotFoundException();
          }
          if (!"pending".equals(invitation.status())) {
            if ("revoked".equals(invitation.status())) return null;
            throw new InvitationNotFoundException();
          }
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                  UPDATE authoring_manuscript_invitations
                     SET status = 'revoked', revoked_at = clock_timestamp()
                   WHERE id = ? AND status = 'pending'
                  """)) {
            update.setObject(1, invitationId);
            if (update.executeUpdate() != 1) throw new InvitationNotFoundException();
          }
          cancelOutbox(connection, invitationId);
          insertInvitationEvent(connection, invitation, actorId, "INVITE_REVOKED");
          return null;
        });
  }

  public Optional<RecipientInvitation> findForRecipient(UUID actorId, UUID invitationId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT invitation.id, invitation.requested_permissions, invitation.expires_at,
                       work.title, work.author
                  FROM authoring_manuscript_invitations invitation
                  JOIN works work ON work.id = invitation.work_id
                  JOIN libraries library ON library.id = work.library_id
                  JOIN users actor ON actor.id = ? AND actor.status = 'active'
                 WHERE invitation.id = ? AND invitation.status = 'pending'
                   AND invitation.expires_at > clock_timestamp()
                   AND actor.email_verified_at IS NOT NULL
                   AND lower(actor.contact_email) = invitation.invited_email
                   AND work.owner_user_id = invitation.owner_user_id
                   AND work.source_kind = 'authored' AND work.visibility = 'private'
                   AND work.status = 'active' AND work.deleted_at IS NULL
                   AND library.owner_user_id = work.owner_user_id
                   AND library.workspace_id IS NULL AND library.visibility = 'private'
                   AND library.deleted_at IS NULL
                   AND EXISTS (SELECT 1 FROM authoring_manuscript_creations creation
                                WHERE creation.work_id = work.id
                                  AND creation.actor_user_id = work.owner_user_id)
                """)) {
      select.setObject(1, actorId);
      select.setObject(2, invitationId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new RecipientInvitation(
                row.getObject("id", UUID.class),
                row.getString("title"),
                row.getString("author"),
                readTextArray(row, "requested_permissions"),
                instant(row, "expires_at")));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load a manuscript invitation", exception);
    }
  }

  public void decide(UUID actorId, UUID invitationId, boolean accept) {
    transaction(
        "Unable to respond to a manuscript invitation",
        connection -> {
          InvitationScope scope = findInvitationScope(connection, invitationId);
          if (scope == null) throw new InvitationNotFoundException();
          requireActiveUser(connection, actorId, true);
          requireOwnerWork(connection, scope.ownerUserId(), scope.workId(), true);
          InvitationRecord invitation = findInvitationForUpdate(connection, invitationId);
          if (invitation == null
              || !scope.workId().equals(invitation.workId())
              || !scope.ownerUserId().equals(invitation.ownerUserId())) {
            throw new InvitationNotFoundException();
          }
          String verifiedEmail = verifiedEmail(connection, actorId);
          if ("accepted".equals(invitation.status())
              && accept
              && actorId.equals(invitation.acceptedByUserId())
              && invitation.invitedEmail().equals(verifiedEmail)) return null;
          if ("rejected".equals(invitation.status())
              && !accept
              && invitation.invitedEmail().equals(verifiedEmail)) return null;
          if (!"pending".equals(invitation.status())
              || !invitation.expiresAt().isAfter(java.time.Instant.now())) {
            throw new InvitationNotFoundException();
          }
          if (verifiedEmail == null || !verifiedEmail.equals(invitation.invitedEmail())) {
            throw new InvitationNotFoundException();
          }
          if (accept) {
            try (PreparedStatement update =
                connection.prepareStatement(
                    """
                    UPDATE authoring_manuscript_invitations
                       SET status = 'accepted', accepted_by_user_id = ?, accepted_at = clock_timestamp()
                     WHERE id = ? AND status = 'pending' AND expires_at > clock_timestamp()
                    """)) {
              update.setObject(1, actorId);
              update.setObject(2, invitationId);
              if (update.executeUpdate() != 1) throw new InvitationNotFoundException();
            }
            String grantAction = upsertAcceptedGrant(connection, invitation, actorId);
            insertInvitationEvent(connection, invitation, actorId, "INVITE_ACCEPTED");
            if (grantAction != null)
              insertContributorEvent(connection, invitation, actorId, grantAction);
          } else {
            try (PreparedStatement update =
                connection.prepareStatement(
                    """
                    UPDATE authoring_manuscript_invitations
                       SET status = 'rejected', rejected_at = clock_timestamp()
                     WHERE id = ? AND status = 'pending' AND expires_at > clock_timestamp()
                    """)) {
              update.setObject(1, invitationId);
              if (update.executeUpdate() != 1) throw new InvitationNotFoundException();
            }
            insertInvitationEvent(connection, invitation, actorId, "INVITE_REJECTED");
          }
          cancelOutbox(connection, invitationId);
          return null;
        });
  }

  public void revokeGrant(UUID actorId, UUID workId, UUID grantId, long expectedVersion) {
    transaction(
        "Unable to revoke a manuscript contributor",
        connection -> {
          requireOwnerWork(connection, actorId, workId, true);
          UUID contributorId;
          String contributorEmail;
          List<String> permissions;
          long version;
          boolean alreadyRevoked;
          try (PreparedStatement select =
              connection.prepareStatement(
                  """
                SELECT contributor.contributor_user_id, contributor.permissions, contributor.version,
                       contributor.revoked_at, lower(recipient.contact_email) AS contact_email
                  FROM authoring_manuscript_contributors contributor
                  JOIN users recipient ON recipient.id = contributor.contributor_user_id
                 WHERE contributor.id = ? AND contributor.work_id = ?
                   AND contributor.owner_user_id = ? FOR UPDATE OF contributor
                """)) {
            select.setObject(1, grantId);
            select.setObject(2, workId);
            select.setObject(3, actorId);
            try (ResultSet row = select.executeQuery()) {
              if (!row.next()) throw new InvitationNotFoundException();
              contributorId = row.getObject("contributor_user_id", UUID.class);
              permissions = readTextArray(row, "permissions");
              version = row.getLong("version");
              contributorEmail = row.getString("contact_email");
              alreadyRevoked = row.getObject("revoked_at") != null;
            }
          }
          if (alreadyRevoked && version == expectedVersion + 1) return null;
          if (version != expectedVersion || alreadyRevoked) {
            throw new GrantPreconditionFailedException();
          }
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                  UPDATE authoring_manuscript_contributors
                     SET revoked_at = clock_timestamp(), version = version + 1
                   WHERE id = ? AND work_id = ? AND owner_user_id = ?
                     AND revoked_at IS NULL AND version = ?
                   RETURNING contributor_user_id, permissions
                  """)) {
            update.setObject(1, grantId);
            update.setObject(2, workId);
            update.setObject(3, actorId);
            update.setLong(4, expectedVersion);
            try (ResultSet row = update.executeQuery()) {
              if (!row.next()) throw new GrantPreconditionFailedException();
              insertContributorEvent(
                  connection, actorId, workId, contributorId, actorId, "REVOKED", permissions);
              revokePendingForEmail(connection, workId, actorId, contributorEmail, actorId);
            }
          }
          return null;
        });
  }

  public List<SharedWork> listSharedWorks(UUID actorId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT contributor.work_id, work.title, work.author, contributor.permissions, contributor.granted_at
                  FROM authoring_manuscript_contributors contributor
                  JOIN works work ON work.id = contributor.work_id
                  JOIN libraries library ON library.id = work.library_id
                  JOIN users owner ON owner.id = contributor.owner_user_id AND owner.status = 'active'
                  JOIN users actor ON actor.id = contributor.contributor_user_id AND actor.status = 'active'
                 WHERE contributor.contributor_user_id = ? AND contributor.revoked_at IS NULL
                   AND work.owner_user_id = contributor.owner_user_id
                   AND work.source_kind = 'authored' AND work.visibility = 'private'
                   AND work.status = 'active' AND work.deleted_at IS NULL
                   AND library.owner_user_id = work.owner_user_id
                   AND library.workspace_id IS NULL AND library.visibility = 'private'
                   AND library.deleted_at IS NULL
                 ORDER BY contributor.granted_at DESC, contributor.work_id DESC LIMIT 100
                """)) {
      select.setObject(1, actorId);
      try (ResultSet rows = select.executeQuery()) {
        List<SharedWork> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new SharedWork(
                  rows.getObject("work_id", UUID.class),
                  rows.getString("title"),
                  rows.getString("author"),
                  readTextArray(rows, "permissions"),
                  instant(rows, "granted_at")));
        }
        return List.copyOf(result);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list shared manuscripts", exception);
    }
  }

  public List<ClaimedDelivery> claimDue() {
    return transaction(
        "Unable to claim manuscript invitation deliveries",
        connection -> {
          expireDueInvitations(connection);
          failExhaustedLeases(connection);
          List<ClaimedDelivery> result = new ArrayList<>();
          try (PreparedStatement select =
              connection.prepareStatement(
                  """
                  SELECT outbox.id, outbox.invitation_id, outbox.attempts + 1 AS attempt
                    FROM authoring_manuscript_invitation_outbox outbox
                    JOIN authoring_manuscript_invitations invitation ON invitation.id = outbox.invitation_id
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
                UUID token = UUID.randomUUID();
                try (PreparedStatement update =
                    connection.prepareStatement(
                        """
                        UPDATE authoring_manuscript_invitation_outbox
                           SET status = 'leased', attempts = attempts + 1, lease_token = ?,
                               lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                               last_safe_failure_code = NULL
                         WHERE id = ?
                        """)) {
                  update.setObject(1, token);
                  update.setLong(2, LEASE_DURATION.toMillis());
                  update.setObject(3, outboxId);
                  update.executeUpdate();
                }
                result.add(
                    new ClaimedDelivery(
                        outboxId, rows.getObject("invitation_id", UUID.class), token));
              }
            }
          }
          return List.copyOf(result);
        });
  }

  public Optional<AuthoringInvitationMailer.Delivery> delivery(ClaimedDelivery claim) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT invitation.invited_email, invitation.requested_permissions
                  FROM authoring_manuscript_invitation_outbox outbox
                  JOIN authoring_manuscript_invitations invitation ON invitation.id = outbox.invitation_id
                 WHERE outbox.id = ? AND outbox.invitation_id = ? AND outbox.lease_token = ?
                   AND outbox.status = 'leased' AND outbox.lease_expires_at > clock_timestamp()
                   AND invitation.status = 'pending' AND invitation.expires_at > clock_timestamp()
                """)) {
      select.setObject(1, claim.outboxId());
      select.setObject(2, claim.invitationId());
      select.setObject(3, claim.leaseToken());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new AuthoringInvitationMailer.Delivery(
                claim.invitationId(),
                row.getString("invited_email"),
                readTextArray(row, "requested_permissions")));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to prepare a manuscript invitation delivery", exception);
    }
  }

  public void complete(ClaimedDelivery claim) {
    updateClaim(
        """
        UPDATE authoring_manuscript_invitation_outbox
           SET status = 'sent', sent_at = clock_timestamp(), lease_token = NULL, lease_expires_at = NULL
         WHERE id = ? AND invitation_id = ? AND lease_token = ? AND status = 'leased'
           AND lease_expires_at > clock_timestamp()
        """,
        claim,
        null);
  }

  public void fail(ClaimedDelivery claim, boolean retryable) {
    transaction(
        "Unable to record manuscript invitation delivery failure",
        connection -> {
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                  UPDATE authoring_manuscript_invitation_outbox
                     SET status = CASE WHEN ? AND attempts < ? THEN 'retry' ELSE 'failed' END,
                         next_attempt_at = clock_timestamp() +
                           (LEAST(3600, power(2, attempts)::int * 30) * interval '1 second'),
                         lease_token = NULL, lease_expires_at = NULL,
                         last_safe_failure_code = CASE WHEN ? THEN 'SMTP_REJECTED' ELSE 'RETRY_EXHAUSTED' END
                   WHERE id = ? AND invitation_id = ? AND lease_token = ? AND status = 'leased'
                  """)) {
            update.setBoolean(1, retryable);
            update.setInt(2, MAX_ATTEMPTS);
            update.setBoolean(3, retryable);
            update.setObject(4, claim.outboxId());
            update.setObject(5, claim.invitationId());
            update.setObject(6, claim.leaseToken());
            update.executeUpdate();
          }
          return null;
        });
  }

  public void cancelClaim(ClaimedDelivery claim) {
    updateClaim(
        """
        UPDATE authoring_manuscript_invitation_outbox
           SET status = 'cancelled', lease_token = NULL, lease_expires_at = NULL,
               last_safe_failure_code = 'INVITATION_NOT_PENDING'
         WHERE id = ? AND invitation_id = ? AND lease_token = ? AND status = 'leased'
        """,
        claim,
        null);
  }

  private String upsertAcceptedGrant(
      Connection connection, InvitationRecord invitation, UUID actorId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT id, permissions, version, revoked_at
              FROM authoring_manuscript_contributors
             WHERE work_id = ? AND contributor_user_id = ? FOR UPDATE
            """)) {
      select.setObject(1, invitation.workId());
      select.setObject(2, actorId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          try (PreparedStatement insert =
              connection.prepareStatement(
                  """
                  INSERT INTO authoring_manuscript_contributors
                      (id, work_id, owner_user_id, contributor_user_id, invitation_id, permissions)
                  VALUES (?, ?, ?, ?, ?, ?)
                  """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, invitation.workId());
            insert.setObject(3, invitation.ownerUserId());
            insert.setObject(4, actorId);
            insert.setObject(5, invitation.id());
            insert.setArray(6, textArray(connection, invitation.permissions()));
            insert.executeUpdate();
          }
          return "GRANTED";
        }
        List<String> oldPermissions = readTextArray(row, "permissions");
        boolean revoked = row.getObject("revoked_at") != null;
        if (oldPermissions.equals(invitation.permissions()) && !revoked) return null;
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                UPDATE authoring_manuscript_contributors
                   SET invitation_id = ?, permissions = ?, version = version + 1, revoked_at = NULL
                 WHERE id = ?
                """)) {
          update.setObject(1, invitation.id());
          update.setArray(2, textArray(connection, invitation.permissions()));
          update.setObject(3, row.getObject("id", UUID.class));
          update.executeUpdate();
        }
        return revoked ? "REACTIVATED" : "PERMISSIONS_CHANGED";
      }
    }
  }

  private void insertInvitationEvent(
      Connection connection, InvitationRecord invitation, UUID actorId, String action)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO authoring_manuscript_invitation_events
                (id, invitation_id, work_id, owner_user_id, actor_user_id, action_code, permissions)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, invitation.id());
      insert.setObject(3, invitation.workId());
      insert.setObject(4, invitation.ownerUserId());
      insert.setObject(5, actorId);
      insert.setString(6, action);
      insert.setArray(7, textArray(connection, invitation.permissions()));
      insert.executeUpdate();
    }
  }

  private void insertContributorEvent(
      Connection connection, InvitationRecord invitation, UUID actorId, String action)
      throws SQLException {
    insertContributorEvent(
        connection,
        invitation.ownerUserId(),
        invitation.workId(),
        actorId,
        actorId,
        action,
        invitation.permissions());
  }

  private void insertContributorEvent(
      Connection connection,
      UUID ownerId,
      UUID workId,
      UUID contributorId,
      UUID actorId,
      String action,
      List<String> permissions)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO authoring_manuscript_contributor_events
                (id, work_id, owner_user_id, contributor_user_id, actor_user_id, action_code,
                 previous_permissions, current_permissions)
            VALUES (?, ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'REVOKED' THEN ? ELSE NULL END,
                    CASE WHEN ? = 'REVOKED' THEN NULL ELSE ? END)
            """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, workId);
      insert.setObject(3, ownerId);
      insert.setObject(4, contributorId);
      insert.setObject(5, actorId);
      insert.setString(6, action);
      insert.setString(7, action);
      insert.setArray(8, textArray(connection, permissions));
      insert.setString(9, action);
      insert.setArray(10, textArray(connection, permissions));
      insert.executeUpdate();
    }
  }

  private void expireMatching(Connection connection, UUID workId, String email, UUID actorId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT id, work_id, owner_user_id, invited_email, requested_permissions, status,
                   accepted_by_user_id, created_at, expires_at
              FROM authoring_manuscript_invitations
             WHERE work_id = ? AND invited_email = ? AND status = 'pending'
             FOR UPDATE
            """)) {
      select.setObject(1, workId);
      select.setString(2, email);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          InstantRow values = new InstantRow(rows);
          if (values.expiresAt().isAfter(java.time.Instant.now())) {
            throw new InvitationConflictException();
          }
          expireOne(connection, values.invitation(), actorId);
        }
      }
    }
  }

  private void expireWorkInvitations(Connection connection, UUID workId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT id, work_id, owner_user_id, invited_email, requested_permissions, status,
                   accepted_by_user_id, created_at, expires_at
              FROM authoring_manuscript_invitations
             WHERE work_id = ? AND status = 'pending' AND expires_at <= clock_timestamp()
             FOR UPDATE
            """)) {
      select.setObject(1, workId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) expireOne(connection, new InstantRow(rows).invitation(), null);
      }
    }
  }

  private void revokePendingForEmail(
      Connection connection, UUID workId, UUID ownerId, String email, UUID actorId)
      throws SQLException {
    if (email == null) return;
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT id, work_id, owner_user_id, invited_email, requested_permissions, status,
                   accepted_by_user_id, created_at, expires_at
              FROM authoring_manuscript_invitations
             WHERE work_id = ? AND owner_user_id = ? AND invited_email = ? AND status = 'pending'
             FOR UPDATE
            """)) {
      select.setObject(1, workId);
      select.setObject(2, ownerId);
      select.setString(3, email);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          InvitationRecord invitation = AuthoringCollaborationRepository.invitation(rows);
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                  UPDATE authoring_manuscript_invitations
                     SET status = 'revoked', revoked_at = clock_timestamp()
                   WHERE id = ? AND status = 'pending'
                  """)) {
            update.setObject(1, invitation.id());
            if (update.executeUpdate() == 1) {
              cancelOutbox(connection, invitation.id());
              insertInvitationEvent(connection, invitation, actorId, "INVITE_REVOKED");
            }
          }
        }
      }
    }
  }

  private void expireDueInvitations(Connection connection) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT id, work_id, owner_user_id, invited_email, requested_permissions, status,
                   accepted_by_user_id, created_at, expires_at
              FROM authoring_manuscript_invitations
             WHERE status = 'pending' AND expires_at <= clock_timestamp()
             ORDER BY expires_at, id LIMIT 100 FOR UPDATE SKIP LOCKED
            """)) {
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) expireOne(connection, new InstantRow(rows).invitation(), null);
      }
    }
  }

  private void expireOne(Connection connection, InvitationRecord invitation, UUID actorId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE authoring_manuscript_invitations SET status = 'expired' WHERE id = ? AND status = 'pending'")) {
      update.setObject(1, invitation.id());
      if (update.executeUpdate() == 1) {
        cancelOutbox(connection, invitation.id());
        insertInvitationEvent(connection, invitation, actorId, "INVITE_EXPIRED");
      }
    }
  }

  private static void cancelOutbox(Connection connection, UUID invitationId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE authoring_manuscript_invitation_outbox
               SET status = 'cancelled', lease_token = NULL, lease_expires_at = NULL,
                   last_safe_failure_code = 'INVITATION_NOT_PENDING'
             WHERE invitation_id = ? AND status IN ('queued', 'retry', 'leased')
            """)) {
      update.setObject(1, invitationId);
      update.executeUpdate();
    }
  }

  private void failExhaustedLeases(Connection connection) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE authoring_manuscript_invitation_outbox
               SET status = 'failed', lease_token = NULL, lease_expires_at = NULL,
                   last_safe_failure_code = 'RETRY_EXHAUSTED'
             WHERE status = 'leased' AND attempts >= ? AND lease_expires_at <= clock_timestamp()
            """)) {
      update.setInt(1, MAX_ATTEMPTS);
      update.executeUpdate();
    }
  }

  private void updateClaim(String sql, ClaimedDelivery claim, Object ignored) {
    transaction(
        "Unable to update manuscript invitation delivery",
        connection -> {
          try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setObject(1, claim.outboxId());
            update.setObject(2, claim.invitationId());
            update.setObject(3, claim.leaseToken());
            update.executeUpdate();
          }
          return null;
        });
  }

  private void enforceRateLimits(Connection connection, UUID actorId, UUID workId, String email)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            """
            SELECT
              (SELECT count(*) FROM authoring_manuscript_invitations
                WHERE invited_by_user_id = ? AND created_at > clock_timestamp() - interval '24 hours') AS actor_count,
              (SELECT count(*) FROM authoring_manuscript_invitations
                WHERE work_id = ? AND created_at > clock_timestamp() - interval '24 hours') AS work_count,
              (SELECT count(*) FROM authoring_manuscript_invitations
                WHERE invited_email = ? AND created_at > clock_timestamp() - interval '24 hours') AS email_count
            """)) {
      query.setObject(1, actorId);
      query.setObject(2, workId);
      query.setString(3, email);
      try (ResultSet row = query.executeQuery()) {
        row.next();
        if (row.getInt("actor_count") >= 50
            || row.getInt("work_count") >= 100
            || row.getInt("email_count") >= 20) throw new InvitationRateLimitException();
      }
    }
  }

  private static void lockQuotaScopes(
      Connection connection, UUID actorId, UUID workId, String email) throws SQLException {
    for (String scope : List.of("actor:" + actorId, "work:" + workId, "email:" + email)) {
      try (PreparedStatement lock =
          connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        lock.setString(1, "novel/authoring-invitation/" + scope);
        lock.execute();
      }
    }
  }

  private void requireOwnerWork(Connection connection, UUID actorId, UUID workId, boolean exclusive)
      throws SQLException {
    requireActiveUser(connection, actorId, exclusive);
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT work.id
              FROM works work
              JOIN libraries library ON library.id = work.library_id
              JOIN authoring_manuscript_creations creation
                ON creation.work_id = work.id AND creation.actor_user_id = work.owner_user_id
             WHERE work.id = ? AND work.owner_user_id = ?
               AND work.source_kind = 'authored' AND work.visibility = 'private'
               AND work.status = 'active' AND work.deleted_at IS NULL
               AND library.owner_user_id = work.owner_user_id
               AND library.workspace_id IS NULL AND library.visibility = 'private'
               AND library.deleted_at IS NULL
             FOR %s OF work, library, creation
            """
                .formatted(exclusive ? "UPDATE" : "SHARE"))) {
      select.setObject(1, workId);
      select.setObject(2, actorId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new InvitationNotFoundException();
      }
    }
  }

  private void requireActiveUser(Connection connection, UUID actorId, boolean exclusive)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM users WHERE id = ? AND status = 'active' FOR "
                + (exclusive ? "UPDATE" : "SHARE"))) {
      select.setObject(1, actorId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new InvitationNotFoundException();
      }
    }
  }

  private static String verifiedEmail(Connection connection, UUID actorId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT lower(contact_email) FROM users WHERE id = ? AND status = 'active' AND email_verified_at IS NOT NULL FOR UPDATE")) {
      select.setObject(1, actorId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getString(1) : null;
      }
    }
  }

  private InvitationRecord findInvitationForUpdate(Connection connection, UUID invitationId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT id, work_id, owner_user_id, invited_email, requested_permissions, status,
                   accepted_by_user_id, created_at, expires_at
              FROM authoring_manuscript_invitations WHERE id = ? FOR UPDATE
            """)) {
      select.setObject(1, invitationId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? invitation(row) : null;
      }
    }
  }

  private InvitationScope findInvitationScope(Connection connection, UUID invitationId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT work_id, owner_user_id FROM authoring_manuscript_invitations WHERE id = ?")) {
      select.setObject(1, invitationId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? new InvitationScope(
                row.getObject("work_id", UUID.class), row.getObject("owner_user_id", UUID.class))
            : null;
      }
    }
  }

  private static InvitationRecord invitation(ResultSet row) throws SQLException {
    return new InvitationRecord(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("owner_user_id", UUID.class),
        row.getString("invited_email"),
        readTextArray(row, "requested_permissions"),
        row.getString("status"),
        (UUID) row.getObject("accepted_by_user_id"),
        instant(row, "created_at"),
        instant(row, "expires_at"));
  }

  private static Array textArray(Connection connection, List<String> values) throws SQLException {
    return connection.createArrayOf("text", values.toArray(String[]::new));
  }

  private static List<String> readTextArray(ResultSet row, String column) throws SQLException {
    Array array = row.getArray(column);
    if (array == null) return List.of();
    try {
      Object value = array.getArray();
      if (value instanceof String[] strings) return List.copyOf(Arrays.asList(strings));
      return List.of();
    } finally {
      array.free();
    }
  }

  private static java.time.Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private <T> T transaction(String message, SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        if (exception instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(message, exception);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(message, exception);
    }
  }

  private record InstantRow(InvitationRecord invitation, java.time.Instant expiresAt) {
    private InstantRow(ResultSet row) throws SQLException {
      this(AuthoringCollaborationRepository.invitation(row), instant(row, "expires_at"));
    }
  }

  private record InvitationScope(UUID workId, UUID ownerUserId) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  public record InvitationRecord(
      UUID id,
      UUID workId,
      UUID ownerUserId,
      String invitedEmail,
      List<String> permissions,
      String status,
      UUID acceptedByUserId,
      java.time.Instant createdAt,
      java.time.Instant expiresAt) {}

  public record GrantSummary(
      UUID id, List<String> permissions, long version, java.time.Instant grantedAt) {}

  public record InvitationSummary(
      UUID id,
      List<String> permissions,
      String status,
      java.time.Instant createdAt,
      java.time.Instant expiresAt) {}

  public record ManagementList(
      List<GrantSummary> collaborators, List<InvitationSummary> invitations) {}

  public record RecipientInvitation(
      UUID id,
      String title,
      String author,
      List<String> permissions,
      java.time.Instant expiresAt) {}

  public record SharedWork(
      UUID workId,
      String title,
      String author,
      List<String> permissions,
      java.time.Instant grantedAt) {}

  public record ClaimedDelivery(UUID outboxId, UUID invitationId, UUID leaseToken) {}

  public static class InvitationNotFoundException extends RuntimeException {}

  public static class InvitationConflictException extends RuntimeException {}

  public static class InvitationRateLimitException extends RuntimeException {}

  public static class GrantConflictException extends RuntimeException {}

  public static class GrantPreconditionFailedException extends RuntimeException {}
}
