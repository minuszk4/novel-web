package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationProviderException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Stores a sanitized fake/local failure and schedules a bounded retry atomically. */
@Repository
@Profile("postgres")
public class TranslationAttemptFailureRepository {

  private final DataSource dataSource;

  public TranslationAttemptFailureRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public FailureResult record(
      TranslationUnitLeaseRepository.Lease lease,
      UUID attemptId,
      TranslationProviderException failure) {
    Objects.requireNonNull(lease, "current lease is required");
    Objects.requireNonNull(attemptId, "attempt id is required");
    Objects.requireNonNull(failure, "provider failure is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        UnitContext unit = lockUnit(connection, lease);
        requireCurrentLease(unit, lease);
        if (!"translating".equals(unit.jobStatus())) throw new FailureNotAllowedException();

        AttemptContext attempt = lockAttempt(connection, lease.unitId(), attemptId);
        if (!"fake-local".equals(unit.providerId())
            || !"fake-local".equals(attempt.providerId())
            || !"running".equals(attempt.outcome())
            || attempt.attemptNo() != unit.attemptCount()
            || (attempt.attemptNo() != lease.attemptCount() + 1
                && attempt.attemptNo() != lease.attemptCount())) {
          throw new FailureNotAllowedException();
        }

        TranslationAttemptFailurePolicy.FailureDecision decision =
            TranslationAttemptFailurePolicy.decide(
                unit.providerId(), attempt.attemptNo(), unit.maxAttempts(), failure);
        finishAttempt(connection, attemptId, decision);
        Instant retryAt = updateUnit(connection, lease, decision);
        JobProgress progress = refreshJobProgress(connection, unit.jobId(), decision.failureCode());
        insertFailureEvent(
            connection, unit.jobId(), lease.unitId(), attemptId, attempt.attemptNo(), decision);
        if ("failed".equals(progress.status())) {
          insertJobFailedEvent(
              connection, unit.jobId(), lease.unitId(), attemptId, attempt.attemptNo());
        }
        connection.commit();
        return new FailureResult(
            unit.jobId(),
            lease.unitId(),
            attemptId,
            attempt.attemptNo(),
            decision.outcome(),
            decision.failureCode(),
            decision.retryScheduled(),
            retryAt,
            progress.status());
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to persist translation attempt failure", exception);
    }
  }

  private static UnitContext lockUnit(
      Connection connection, TranslationUnitLeaseRepository.Lease lease) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT unit.job_id, unit.source_revision_id, unit.status AS unit_status,
                       unit.lease_owner, unit.lease_token, unit.attempt_count, unit.max_attempts,
                       unit.lease_until > clock_timestamp() AS lease_live,
                       job.status AS job_status, job.provider_id
                FROM translation_units unit JOIN translation_jobs job ON job.id = unit.job_id
                WHERE unit.id = ? AND unit.job_id = ? FOR UPDATE OF unit
                """)) {
      select.setObject(1, lease.unitId());
      select.setObject(2, lease.jobId());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new StaleLeaseException();
        return new UnitContext(
            row.getObject("job_id", UUID.class),
            row.getObject("source_revision_id", UUID.class),
            row.getString("unit_status"),
            row.getString("lease_owner"),
            row.getObject("lease_token", UUID.class),
            row.getInt("attempt_count"),
            row.getInt("max_attempts"),
            row.getBoolean("lease_live"),
            row.getString("job_status"),
            row.getString("provider_id"));
      }
    }
  }

  private static AttemptContext lockAttempt(Connection connection, UUID unitId, UUID attemptId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT attempt_no, provider_id, outcome FROM translation_attempts "
                + "WHERE id = ? AND unit_id = ? FOR UPDATE")) {
      select.setObject(1, attemptId);
      select.setObject(2, unitId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new AttemptNotFoundException();
        return new AttemptContext(
            row.getInt("attempt_no"), row.getString("provider_id"), row.getString("outcome"));
      }
    }
  }

  private static void requireCurrentLease(
      UnitContext unit, TranslationUnitLeaseRepository.Lease lease) {
    if (!"running".equals(unit.unitStatus())
        || !lease.workerId().equals(unit.leaseOwner())
        || !lease.leaseToken().equals(unit.leaseToken())
        || !lease.sourceRevisionId().equals(unit.sourceRevisionId())
        || !unit.leaseLive()
        || unit.attemptCount() > unit.maxAttempts()) {
      throw new StaleLeaseException();
    }
  }

  private static void finishAttempt(
      Connection connection,
      UUID attemptId,
      TranslationAttemptFailurePolicy.FailureDecision decision)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_attempts
                SET outcome = ?, failure_code = ?,
                    duration_ms = greatest(0, floor(extract(epoch FROM
                        (clock_timestamp() - started_at)) * 1000)::bigint),
                    completed_at = clock_timestamp()
                WHERE id = ? AND outcome = 'running' AND completed_at IS NULL
                """)) {
      update.setString(1, decision.outcome());
      update.setString(2, decision.failureCode());
      update.setObject(3, attemptId);
      if (update.executeUpdate() != 1) throw new FailureConflictException();
    }
  }

  private static Instant updateUnit(
      Connection connection,
      TranslationUnitLeaseRepository.Lease lease,
      TranslationAttemptFailurePolicy.FailureDecision decision)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_units
                SET status = ?,
                    available_at = CASE WHEN ? THEN
                        clock_timestamp() + (? * INTERVAL '1 millisecond') ELSE available_at END,
                    last_failure_code = ?, lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, heartbeat_at = NULL, updated_at = clock_timestamp()
                WHERE id = ? AND job_id = ? AND source_revision_id = ? AND status = 'running'
                  AND lease_owner = ? AND lease_token = ? AND lease_until > clock_timestamp()
                RETURNING available_at
                """)) {
      update.setString(1, decision.retryScheduled() ? "retry_wait" : "failed");
      update.setBoolean(2, decision.retryScheduled());
      update.setLong(3, decision.retryDelay() == null ? 0 : decision.retryDelay().toMillis());
      update.setString(4, decision.failureCode());
      update.setObject(5, lease.unitId());
      update.setObject(6, lease.jobId());
      update.setObject(7, lease.sourceRevisionId());
      update.setString(8, lease.workerId());
      update.setObject(9, lease.leaseToken());
      try (ResultSet row = update.executeQuery()) {
        if (!row.next()) throw new StaleLeaseException();
        OffsetDateTime availableAt = row.getObject("available_at", OffsetDateTime.class);
        return decision.retryScheduled() ? availableAt.toInstant() : null;
      }
    }
  }

  private static JobProgress refreshJobProgress(
      Connection connection, UUID jobId, String failureCode) throws SQLException {
    UnitCounts counts = loadCounts(connection, jobId);
    String status =
        counts.pendingUnits() == 0
            ? "failed"
            : counts.readyUnits() == 0 ? "retry_wait" : "translating";
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_jobs
                SET completed_units = ?, failed_units = ?, status = ?,
                    failure_code = CASE WHEN ? = 'failed' THEN ? ELSE NULL END,
                    completed_at = CASE WHEN ? = 'failed' THEN clock_timestamp() ELSE NULL END,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'translating'
                """)) {
      update.setInt(1, counts.completedUnits());
      update.setInt(2, counts.failedUnits());
      update.setString(3, status);
      update.setString(4, status);
      update.setString(5, failureCode);
      update.setString(6, status);
      update.setObject(7, jobId);
      if (update.executeUpdate() != 1) throw new FailureNotAllowedException();
    }
    return new JobProgress(status);
  }

  private static UnitCounts loadCounts(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT count(*) FILTER (WHERE status = 'succeeded')::integer AS completed_units,
                       count(*) FILTER (WHERE status = 'failed')::integer AS failed_units,
                       count(*) FILTER (WHERE status IN ('queued', 'running', 'retry_wait'))::integer
                           AS pending_units,
                       count(*) FILTER (WHERE status IN ('queued', 'running'))::integer AS ready_units
                FROM translation_units WHERE job_id = ?
                """)) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new FailureConflictException();
        return new UnitCounts(
            row.getInt("completed_units"),
            row.getInt("failed_units"),
            row.getInt("pending_units"),
            row.getInt("ready_units"));
      }
    }
  }

  private static void insertFailureEvent(
      Connection connection,
      UUID jobId,
      UUID unitId,
      UUID attemptId,
      int attemptNo,
      TranslationAttemptFailurePolicy.FailureDecision decision)
      throws SQLException {
    String eventType =
        decision.retryScheduled() ? "translation.unit.retry_scheduled" : "translation.unit.failed";
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events
                    (event_id, aggregate_id, event_type, schema_version, deduplication_key,
                     correlation_id, payload)
                VALUES (?, ?, ?, 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'unitId', ?::text,
                                           'attemptId', ?::text, 'attemptNo', ?))
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, jobId);
      insert.setString(3, eventType);
      insert.setString(4, eventType.replace('.', '-') + "-" + attemptId);
      insert.setObject(5, attemptId);
      insert.setObject(6, jobId);
      insert.setObject(7, unitId);
      insert.setObject(8, attemptId);
      insert.setInt(9, attemptNo);
      insert.executeUpdate();
    }
  }

  private static void insertJobFailedEvent(
      Connection connection, UUID jobId, UUID unitId, UUID attemptId, int attemptNo)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events
                    (event_id, aggregate_id, event_type, schema_version, deduplication_key,
                     correlation_id, payload)
                VALUES (?, ?, 'translation.job.failed', 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'unitId', ?::text,
                                           'attemptId', ?::text, 'attemptNo', ?))
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, jobId);
      insert.setString(3, "job-failed-" + jobId);
      insert.setObject(4, attemptId);
      insert.setObject(5, jobId);
      insert.setObject(6, unitId);
      insert.setObject(7, attemptId);
      insert.setInt(8, attemptNo);
      insert.executeUpdate();
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record UnitContext(
      UUID jobId,
      UUID sourceRevisionId,
      String unitStatus,
      String leaseOwner,
      UUID leaseToken,
      int attemptCount,
      int maxAttempts,
      boolean leaseLive,
      String jobStatus,
      String providerId) {}

  private record AttemptContext(int attemptNo, String providerId, String outcome) {}

  private record UnitCounts(
      int completedUnits, int failedUnits, int pendingUnits, int readyUnits) {}

  private record JobProgress(String status) {}

  public record FailureResult(
      UUID jobId,
      UUID unitId,
      UUID attemptId,
      int attemptNo,
      String outcome,
      String failureCode,
      boolean retryScheduled,
      Instant retryAt,
      String jobStatus) {}

  public static final class StaleLeaseException extends RuntimeException {}

  public static final class FailureNotAllowedException extends RuntimeException {}

  public static final class AttemptNotFoundException extends RuntimeException {}

  public static final class FailureConflictException extends RuntimeException {}
}
