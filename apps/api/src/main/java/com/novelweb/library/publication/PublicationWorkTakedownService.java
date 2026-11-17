package com.novelweb.library.publication;

import com.novelweb.identity.auth.PolicyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Revokes every publication-scope grant for one work and makes the work private atomically. */
@Service
@Profile("postgres")
public class PublicationWorkTakedownService {

  private final DataSource dataSource;
  private final PolicyService policyService;
  private final PublicationPurgeTargetRegistry purgeTargetRegistry;
  private final PublicationTakedownPurgeService purgeService;

  public PublicationWorkTakedownService(
      DataSource dataSource,
      PolicyService policyService,
      PublicationPurgeTargetRegistry purgeTargetRegistry,
      PublicationTakedownPurgeService purgeService) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
    this.purgeTargetRegistry =
        Objects.requireNonNull(purgeTargetRegistry, "purge target registry is required");
    this.purgeService = Objects.requireNonNull(purgeService, "purge service is required");
  }

  public TakedownResult takeDown(
      UUID actorId, UUID workId, String idempotencyKey, String reasonCode) {
    Objects.requireNonNull(actorId, "actor id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");
    Objects.requireNonNull(reasonCode, "reason code is required");

    if (activeRole(actorId).isEmpty()) return TakedownResult.reviewerRequired();
    PolicyService.RequiredAcceptance initialAcceptance = policyService.requiredAcceptance(actorId);
    if (initialAcceptance != PolicyService.RequiredAcceptance.COMPLETE) {
      return initialAcceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED
          ? TakedownResult.policyNotConfigured()
          : TakedownResult.policyRequired();
    }

    byte[] keyDigest = idempotencyDigest(actorId, idempotencyKey);
    byte[] requestDigest = requestDigest(actorId, workId, reasonCode);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<LockedWork> lockedWork = lockEligibleWork(connection, actorId, workId);
        if (lockedWork.isEmpty()) {
          connection.commit();
          return TakedownResult.notFound();
        }
        LockedWork work = lockedWork.orElseThrow();

        Optional<String> lockedRole = activeRole(connection, actorId);
        if (lockedRole.isEmpty()) {
          connection.commit();
          return TakedownResult.reviewerRequired();
        }
        PolicyService.RequiredAcceptance acceptance = policyService.requiredAcceptance(actorId);
        if (acceptance == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return TakedownResult.policyRequired();
        }
        if (acceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return TakedownResult.policyNotConfigured();
        }

        Optional<TakedownReceipt> prior = findReceipt(connection, actorId, keyDigest);
        if (prior.isPresent()) {
          TakedownReceipt receipt = prior.orElseThrow();
          if (!MessageDigest.isEqual(receipt.requestSha256(), requestDigest)) {
            connection.commit();
            return TakedownResult.keyReused();
          }
          connection.commit();
          return TakedownResult.replayed(receipt.publicReceipt());
        }

        List<UUID> approvals = activeApprovalIds(connection, workId);
        int revokedCount = revokeApprovals(connection, actorId, reasonCode, approvals);
        setPrivate(connection, workId);
        TakedownReceipt receipt =
            appendTakedownEvent(
                connection,
                actorId,
                lockedRole.orElseThrow(),
                work,
                reasonCode,
                keyDigest,
                requestDigest,
                revokedCount,
                purgeTargetRegistry.targetCodes().isEmpty() ? "NOT_CONFIGURED" : "REQUESTED");
        purgeService.enqueue(connection, receipt.id(), workId, receipt.createdAt());
        connection.commit();
        return TakedownResult.created(receipt.publicReceipt());
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to revoke publication access", exception);
    }
  }

  private Optional<String> activeRole(UUID actorId) {
    try (Connection connection = dataSource.getConnection()) {
      return activeRole(connection, actorId);
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to verify publication takedown role", exception);
    }
  }

  private static Optional<String> activeRole(Connection connection, UUID actorId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT assignment.role_code
                  FROM security_role_assignments assignment
                  JOIN users actor
                    ON actor.id = assignment.user_id AND actor.status = 'active'
                 WHERE assignment.user_id = ?
                   AND assignment.role_code IN ('system_admin', 'publication_reviewer')
                   AND assignment.assigned_at <= clock_timestamp()
                   AND assignment.revoked_at IS NULL
                 ORDER BY CASE assignment.role_code
                            WHEN 'system_admin' THEN 0 ELSE 1 END,
                          assignment.assigned_at DESC, assignment.id DESC
                 LIMIT 1
                 FOR SHARE OF assignment, actor
                """)) {
      select.setObject(1, actorId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
      }
    }
  }

  private static Optional<LockedWork> lockEligibleWork(
      Connection connection, UUID actorId, UUID workId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, owner_user_id, visibility
                  FROM works
                 WHERE id = ?
                   AND owner_user_id <> ?
                   AND source_kind = 'authored'
                   AND deleted_at IS NULL
                 FOR UPDATE
                """)) {
      select.setObject(1, workId);
      select.setObject(2, actorId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new LockedWork(
                row.getObject("id", UUID.class),
                row.getObject("owner_user_id", UUID.class),
                row.getString("visibility")));
      }
    }
  }

  private static Optional<TakedownReceipt> findReceipt(
      Connection connection, UUID actorId, byte[] keyDigest) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, work_id, actor_role_code, reason_code, request_sha256,
                       revoked_approval_count, previous_visibility, current_visibility,
                       external_cache_purge_status, created_at
                  FROM publication_work_takedown_events
                 WHERE actor_user_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, actorId);
      select.setBytes(2, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readReceipt(row)) : Optional.empty();
      }
    }
  }

  private static List<UUID> activeApprovalIds(Connection connection, UUID workId)
      throws SQLException {
    List<UUID> approvals = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT approval.id
                  FROM publication_scope_access_approvals approval
                 WHERE approval.work_id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM publication_scope_access_revocations revocation
                        WHERE revocation.approval_id = approval.id
                   )
                 ORDER BY CASE approval.scope_code
                            WHEN 'PUBLIC_METADATA' THEN 0
                            WHEN 'CHAPTER_TEXT' THEN 1
                            WHEN 'COMMERCIAL_SALE' THEN 2
                            WHEN 'TRANSLATION_ADAPTATION' THEN 3
                            ELSE 4 END,
                          approval.id
                 FOR UPDATE OF approval
                """)) {
      select.setObject(1, workId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) approvals.add(rows.getObject(1, UUID.class));
      }
    }
    return List.copyOf(approvals);
  }

  private static int revokeApprovals(
      Connection connection, UUID actorId, String reasonCode, List<UUID> approvals)
      throws SQLException {
    int revoked = 0;
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_scope_access_revocations (
                    approval_id, revoked_by_user_id, reason_code)
                VALUES (?, ?, ?)
                ON CONFLICT (approval_id) DO NOTHING
                """)) {
      for (UUID approvalId : approvals) {
        insert.setObject(1, approvalId);
        insert.setObject(2, actorId);
        insert.setString(3, reasonCode);
        revoked += insert.executeUpdate();
      }
    }
    return revoked;
  }

  private static void setPrivate(Connection connection, UUID workId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement("UPDATE works SET visibility = 'private' WHERE id = ?")) {
      update.setObject(1, workId);
      if (update.executeUpdate() != 1) throw new SQLException("The publication work disappeared");
    }
  }

  private static TakedownReceipt appendTakedownEvent(
      Connection connection,
      UUID actorId,
      String actorRoleCode,
      LockedWork work,
      String reasonCode,
      byte[] keyDigest,
      byte[] requestDigest,
      int revokedCount,
      String purgeStatus)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_work_takedown_events (
                    id, work_id, actor_user_id, actor_role_code, reason_code,
                    idempotency_key_sha256, request_sha256, revoked_approval_count,
                    previous_visibility, external_cache_purge_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, work_id, actor_role_code, reason_code, request_sha256,
                          revoked_approval_count, previous_visibility, current_visibility,
                          external_cache_purge_status, created_at
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, work.id());
      insert.setObject(3, actorId);
      insert.setString(4, actorRoleCode);
      insert.setString(5, reasonCode);
      insert.setBytes(6, keyDigest);
      insert.setBytes(7, requestDigest);
      insert.setInt(8, revokedCount);
      insert.setString(9, work.visibility());
      insert.setString(10, purgeStatus);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Publication takedown event returned no receipt");
        return readReceipt(row);
      }
    }
  }

  private static TakedownReceipt readReceipt(ResultSet row) throws SQLException {
    return new TakedownReceipt(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getString("actor_role_code"),
        row.getString("reason_code"),
        row.getBytes("request_sha256"),
        row.getInt("revoked_approval_count"),
        row.getString("previous_visibility"),
        row.getString("current_visibility"),
        row.getString("external_cache_purge_status"),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static byte[] idempotencyDigest(UUID actorId, String key) {
    return sha256(String.join("\n", "publication-work-takedown-key-v1", actorId.toString(), key));
  }

  private static byte[] requestDigest(UUID actorId, UUID workId, String reasonCode) {
    return sha256(
        String.join(
            "\n",
            "publication-work-takedown-request-v1",
            actorId.toString(),
            workId.toString(),
            reasonCode));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record LockedWork(UUID id, UUID ownerId, String visibility) {}

  private record TakedownReceipt(
      UUID id,
      UUID workId,
      String actorRoleCode,
      String reasonCode,
      byte[] requestSha256,
      int revokedApprovalCount,
      String previousVisibility,
      String currentVisibility,
      String externalCachePurgeStatus,
      Instant createdAt) {
    private TakedownReceipt {
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }

    private PublicReceipt publicReceipt() {
      return new PublicReceipt(
          id,
          workId,
          "ACCESS_REVOKED",
          externalCachePurgeStatus,
          actorRoleCode,
          reasonCode,
          revokedApprovalCount,
          previousVisibility,
          currentVisibility,
          createdAt);
    }
  }

  public record PublicReceipt(
      UUID eventId,
      UUID workId,
      String accessState,
      String externalCachePurgeStatus,
      String actorRoleCode,
      String reasonCode,
      int revokedApprovalCount,
      String previousVisibility,
      String currentVisibility,
      Instant createdAt) {}

  public record TakedownResult(Outcome outcome, PublicReceipt receipt) {
    static TakedownResult created(PublicReceipt receipt) {
      return new TakedownResult(Outcome.CREATED, receipt);
    }

    static TakedownResult replayed(PublicReceipt receipt) {
      return new TakedownResult(Outcome.REPLAYED, receipt);
    }

    static TakedownResult reviewerRequired() {
      return new TakedownResult(Outcome.REVIEWER_REQUIRED, null);
    }

    static TakedownResult policyRequired() {
      return new TakedownResult(Outcome.POLICY_REQUIRED, null);
    }

    static TakedownResult policyNotConfigured() {
      return new TakedownResult(Outcome.POLICY_NOT_CONFIGURED, null);
    }

    static TakedownResult notFound() {
      return new TakedownResult(Outcome.NOT_FOUND, null);
    }

    static TakedownResult keyReused() {
      return new TakedownResult(Outcome.KEY_REUSED, null);
    }
  }

  public enum Outcome {
    CREATED,
    REPLAYED,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    NOT_FOUND,
    KEY_REUSED
  }
}
