package com.novelweb.library.publication;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.library.publication.PublicationPurgeTargetRegistry.Target;
import com.novelweb.library.publication.PublicationPurgeTargetRegistry.TargetCode;
import com.novelweb.library.publication.PublicationTakedownPurgeTransport.DeliveryResult;
import com.novelweb.library.publication.PublicationTakedownPurgeTransport.Outcome;
import com.novelweb.library.publication.PublicationTakedownPurgeTransport.PurgeCommand;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Transactional purge outbox, bounded delivery worker and private reviewer status query. */
@Service
@Profile("postgres")
public class PublicationTakedownPurgeService {

  private static final int MAX_ATTEMPTS = 8;
  private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);

  private final DataSource dataSource;
  private final PolicyService policyService;
  private final PublicationPurgeTargetRegistry targetRegistry;
  private final PublicationTakedownPurgeTransport transport;

  public PublicationTakedownPurgeService(
      DataSource dataSource,
      PolicyService policyService,
      PublicationPurgeTargetRegistry targetRegistry,
      PublicationTakedownPurgeTransport transport) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
    this.targetRegistry =
        Objects.requireNonNull(targetRegistry, "purge target registry is required");
    this.transport = Objects.requireNonNull(transport, "purge transport is required");
  }

  public void enqueue(Connection connection, UUID eventId, UUID workId, Instant requestedAt)
      throws SQLException {
    if (targetRegistry.targetCodes().isEmpty()) return;
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_takedown_purge_requests (
                    id, event_id, work_id, target_code, requested_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, target_code) DO NOTHING
                """)) {
      for (TargetCode targetCode : targetRegistry.targetCodes()) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, eventId);
        insert.setObject(3, workId);
        insert.setString(4, targetCode.name());
        insert.setObject(5, OffsetDateTime.ofInstant(requestedAt, ZoneOffset.UTC));
        insert.setObject(6, OffsetDateTime.ofInstant(requestedAt, ZoneOffset.UTC));
        if (insert.executeUpdate() != 1) {
          throw new SQLException("Publication purge request was not recorded");
        }
      }
    }
  }

  /** Runs one bounded batch. Database leases prevent duplicate active delivery across instances. */
  public int processDueBatch() {
    return processDueRequests(null);
  }

  /**
   * Runs a bounded work-scoped batch for controlled operations and deterministic integration tests.
   */
  public int processDueRequestsForWork(UUID workId) {
    return processDueRequests(Objects.requireNonNull(workId, "work id is required"));
  }

  private int processDueRequests(UUID workId) {
    recoverExpiredLeases(workId);
    List<ClaimedRequest> claimed = claimDueRequests(workId);
    for (ClaimedRequest request : claimed) {
      Target target = targetRegistry.target(request.targetCode());
      if (target == null) {
        complete(request, Outcome.TRANSPORT_FAILED, "TARGET_NOT_CONFIGURED");
        continue;
      }
      DeliveryResult result;
      try {
        result =
            transport.purge(
                target,
                new PurgeCommand(
                    request.eventId(),
                    request.workId(),
                    request.targetCode(),
                    request.requestId().toString(),
                    request.requestedAt()));
      } catch (RuntimeException exception) {
        result = DeliveryResult.transportFailed();
      }
      complete(request, result.outcome(), failureCode(result.outcome()));
    }
    return claimed.size();
  }

  public StatusResult status(UUID actorId, UUID workId) {
    Objects.requireNonNull(actorId, "actor id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      if (activeRole(connection, actorId).isEmpty()) return StatusResult.reviewerRequired();
      PolicyService.RequiredAcceptance acceptance = policyService.requiredAcceptance(actorId);
      if (acceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
        return StatusResult.policyNotConfigured();
      }
      if (acceptance != PolicyService.RequiredAcceptance.COMPLETE) {
        return StatusResult.policyRequired();
      }

      Optional<TakedownEvent> event = latestEvent(connection, actorId, workId);
      if (event.isEmpty()) return StatusResult.notFound();
      TakedownEvent latest = event.orElseThrow();
      List<TargetStatus> targets = readTargetStatuses(connection, latest.id());
      String overall = overallStatus(latest.initialStatus(), targets);
      return StatusResult.available(
          new PurgeStatus(
              latest.id(), latest.workId(), overall, latest.createdAt(), List.copyOf(targets)));
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read publication purge status", exception);
    }
  }

  private void recoverExpiredLeases(UUID workId) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        List<ExpiredLease> expired = new ArrayList<>();
        String workFilter = workId == null ? "" : " AND work_id = ?";
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, attempt_count
                      FROM publication_takedown_purge_requests
                     WHERE status = 'IN_PROGRESS' AND lease_until <= clock_timestamp()
                    """
                    + workFilter
                    + " ORDER BY lease_until, id LIMIT ? FOR UPDATE SKIP LOCKED")) {
          int parameter = 1;
          if (workId != null) select.setObject(parameter++, workId);
          select.setInt(parameter, targetRegistry.batchSize());
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next())
              expired.add(new ExpiredLease(rows.getObject(1, UUID.class), rows.getInt(2)));
          }
        }
        for (ExpiredLease lease : expired) {
          boolean terminal = lease.attemptNumber() >= MAX_ATTEMPTS;
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                      UPDATE publication_takedown_purge_requests
                         SET status = ?, next_attempt_at = ?, lease_token = NULL, lease_until = NULL,
                             last_safe_failure_code = 'LEASE_EXPIRED'
                       WHERE id = ?
                      """)) {
            update.setString(1, terminal ? "FAILED" : "REQUESTED");
            update.setObject(
                2, OffsetDateTime.now(ZoneOffset.UTC).plus(retryDelay(lease.attemptNumber())));
            update.setObject(3, lease.requestId());
            update.executeUpdate();
          }
          insertAttempt(
              connection,
              lease.requestId(),
              lease.attemptNumber(),
              "LEASE_EXPIRED",
              "LEASE_EXPIRED");
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to recover expired publication purge leases", exception);
    }
  }

  private List<ClaimedRequest> claimDueRequests(UUID workId) {
    List<ClaimedRequest> claimed = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String workFilter = workId == null ? "" : " AND work_id = ?";
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, event_id, work_id, target_code, attempt_count, requested_at
                      FROM publication_takedown_purge_requests
                     WHERE status = 'REQUESTED'
                       AND next_attempt_at <= clock_timestamp()
                       AND attempt_count < ?
                    """
                    + workFilter
                    + " ORDER BY next_attempt_at, requested_at, id LIMIT ? FOR UPDATE SKIP LOCKED")) {
          select.setInt(1, MAX_ATTEMPTS);
          if (workId != null) select.setObject(2, workId);
          select.setInt(workId == null ? 2 : 3, targetRegistry.batchSize());
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              claimed.add(
                  new ClaimedRequest(
                      rows.getObject("id", UUID.class),
                      rows.getObject("event_id", UUID.class),
                      rows.getObject("work_id", UUID.class),
                      TargetCode.valueOf(rows.getString("target_code")),
                      rows.getInt("attempt_count") + 1,
                      rows.getObject("requested_at", OffsetDateTime.class).toInstant(),
                      UUID.randomUUID()));
            }
          }
        }
        for (ClaimedRequest request : claimed) {
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                      UPDATE publication_takedown_purge_requests
                         SET status = 'IN_PROGRESS', attempt_count = ?, lease_token = ?,
                             lease_until = clock_timestamp() + (? * interval '1 millisecond')
                       WHERE id = ? AND status = 'REQUESTED'
                      """)) {
            update.setInt(1, request.attemptNumber());
            update.setObject(2, request.leaseToken());
            update.setLong(3, LEASE_DURATION.toMillis());
            update.setObject(4, request.requestId());
            if (update.executeUpdate() != 1) throw new SQLException("Purge request lease was lost");
          }
        }
        connection.commit();
        return List.copyOf(claimed);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim publication purge requests", exception);
    }
  }

  private void complete(ClaimedRequest request, Outcome outcome, String failureCode) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        boolean owned;
        try (PreparedStatement lock =
            connection.prepareStatement(
                """
                    SELECT 1 FROM publication_takedown_purge_requests
                     WHERE id = ? AND status = 'IN_PROGRESS' AND lease_token = ?
                     FOR UPDATE
                    """)) {
          lock.setObject(1, request.requestId());
          lock.setObject(2, request.leaseToken());
          try (ResultSet row = lock.executeQuery()) {
            owned = row.next();
          }
        }
        if (!owned) {
          connection.commit();
          return;
        }

        boolean acknowledged = outcome == Outcome.ACKNOWLEDGED;
        boolean terminal = !acknowledged && request.attemptNumber() >= MAX_ATTEMPTS;
        String nextStatus = acknowledged ? "ACKNOWLEDGED" : terminal ? "FAILED" : "REQUESTED";
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE publication_takedown_purge_requests
                       SET status = ?, lease_token = NULL, lease_until = NULL,
                           next_attempt_at = ?, last_safe_failure_code = ?, acknowledged_at = ?
                     WHERE id = ?
                    """)) {
          update.setString(1, nextStatus);
          update.setObject(
              2,
              OffsetDateTime.now(ZoneOffset.UTC)
                  .plus(acknowledged ? Duration.ZERO : retryDelay(request.attemptNumber())));
          update.setString(3, failureCode);
          update.setObject(4, acknowledged ? OffsetDateTime.now(ZoneOffset.UTC) : null);
          update.setObject(5, request.requestId());
          update.executeUpdate();
        }
        String attemptOutcome = acknowledged ? "ACKNOWLEDGED" : outcome.name();
        insertAttempt(
            connection, request.requestId(), request.attemptNumber(), attemptOutcome, failureCode);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record publication purge delivery", exception);
    }
  }

  private static void insertAttempt(
      Connection connection, UUID requestId, int attemptNumber, String outcome, String failureCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_takedown_purge_attempts (
                    id, request_id, attempt_number, outcome, safe_failure_code)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (request_id, attempt_number) DO NOTHING
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, requestId);
      insert.setInt(3, attemptNumber);
      insert.setString(4, outcome);
      insert.setString(5, failureCode);
      insert.executeUpdate();
    }
  }

  private Optional<TakedownEvent> latestEvent(Connection connection, UUID actorId, UUID workId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event.id, event.work_id, event.external_cache_purge_status, event.created_at
                  FROM works work
                  JOIN publication_work_takedown_events event ON event.work_id = work.id
                 WHERE work.id = ? AND work.owner_user_id <> ?
                   AND work.source_kind = 'authored' AND work.deleted_at IS NULL
                 ORDER BY event.created_at DESC, event.id DESC
                 LIMIT 1
                """)) {
      select.setObject(1, workId);
      select.setObject(2, actorId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new TakedownEvent(
                row.getObject("id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getString("external_cache_purge_status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant()));
      }
    }
  }

  private static List<TargetStatus> readTargetStatuses(Connection connection, UUID eventId)
      throws SQLException {
    List<TargetStatus> statuses = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT target_code, status, attempt_count, last_safe_failure_code,
                       requested_at, acknowledged_at
                  FROM publication_takedown_purge_requests
                 WHERE event_id = ?
                 ORDER BY target_code
                """)) {
      select.setObject(1, eventId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          OffsetDateTime acknowledgedAt = rows.getObject("acknowledged_at", OffsetDateTime.class);
          statuses.add(
              new TargetStatus(
                  rows.getString("target_code"),
                  rows.getString("status"),
                  rows.getInt("attempt_count"),
                  rows.getString("last_safe_failure_code"),
                  rows.getObject("requested_at", OffsetDateTime.class).toInstant(),
                  acknowledgedAt == null ? null : acknowledgedAt.toInstant()));
        }
      }
    }
    return List.copyOf(statuses);
  }

  private static String overallStatus(String initialStatus, List<TargetStatus> targets) {
    if ("NOT_CONFIGURED".equals(initialStatus)) return "NOT_CONFIGURED";
    if (targets.isEmpty()) return "REQUESTED";
    if (targets.stream().anyMatch(target -> "FAILED".equals(target.status()))) return "FAILED";
    if (targets.stream().allMatch(target -> "ACKNOWLEDGED".equals(target.status()))) {
      return "ACKNOWLEDGED";
    }
    if (targets.stream().anyMatch(target -> "IN_PROGRESS".equals(target.status()))) {
      return "IN_PROGRESS";
    }
    return "REQUESTED";
  }

  private static Optional<String> activeRole(Connection connection, UUID actorId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT assignment.role_code
                  FROM security_role_assignments assignment
                  JOIN users actor ON actor.id = assignment.user_id AND actor.status = 'active'
                 WHERE assignment.user_id = ?
                   AND assignment.role_code IN ('system_admin', 'publication_reviewer')
                   AND assignment.assigned_at <= clock_timestamp()
                   AND assignment.revoked_at IS NULL
                 ORDER BY CASE assignment.role_code WHEN 'system_admin' THEN 0 ELSE 1 END,
                          assignment.assigned_at DESC, assignment.id DESC
                 LIMIT 1
                """)) {
      select.setObject(1, actorId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
      }
    }
  }

  private static Duration retryDelay(int attemptNumber) {
    long seconds = Math.min(15L * 60L, 10L << Math.max(0, attemptNumber - 1));
    return Duration.ofSeconds(seconds);
  }

  private static String failureCode(Outcome outcome) {
    return switch (outcome) {
      case ACKNOWLEDGED -> null;
      case REMOTE_REJECTED -> "REMOTE_REJECTED";
      case TRANSPORT_FAILED -> "TRANSPORT_FAILED";
      case INTERRUPTED -> "DELIVERY_INTERRUPTED";
    };
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record ExpiredLease(UUID requestId, int attemptNumber) {}

  private record ClaimedRequest(
      UUID requestId,
      UUID eventId,
      UUID workId,
      TargetCode targetCode,
      int attemptNumber,
      Instant requestedAt,
      UUID leaseToken) {}

  private record TakedownEvent(UUID id, UUID workId, String initialStatus, Instant createdAt) {}

  public record TargetStatus(
      String targetCode,
      String status,
      int attemptCount,
      String lastSafeFailureCode,
      Instant requestedAt,
      Instant acknowledgedAt) {}

  public record PurgeStatus(
      UUID eventId, UUID workId, String status, Instant requestedAt, List<TargetStatus> targets) {}

  public record StatusResult(StatusOutcome outcome, PurgeStatus status) {
    static StatusResult available(PurgeStatus status) {
      return new StatusResult(StatusOutcome.AVAILABLE, status);
    }

    static StatusResult reviewerRequired() {
      return new StatusResult(StatusOutcome.REVIEWER_REQUIRED, null);
    }

    static StatusResult policyRequired() {
      return new StatusResult(StatusOutcome.POLICY_REQUIRED, null);
    }

    static StatusResult policyNotConfigured() {
      return new StatusResult(StatusOutcome.POLICY_NOT_CONFIGURED, null);
    }

    static StatusResult notFound() {
      return new StatusResult(StatusOutcome.NOT_FOUND, null);
    }
  }

  public enum StatusOutcome {
    AVAILABLE,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    NOT_FOUND
  }
}
