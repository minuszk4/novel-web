package com.novelweb.translation.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Cancels active fake-local translation work and fences its outstanding worker leases. */
@Repository
@Profile("postgres")
public class TranslationJobCancelRepository {

  private static final Set<String> CANCELLABLE_JOB_STATUSES =
      Set.of("queued", "preparing", "translating", "retry_wait", "cancel_requested");
  private static final Set<String> CANCELLABLE_UNIT_STATUSES =
      Set.of("queued", "running", "retry_wait");

  private final DataSource dataSource;

  public TranslationJobCancelRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public CancelResult cancel(UUID actorUserId, UUID jobId, String deduplicationKey) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        if (!hasWritableAccess(connection, actorUserId, jobId)) throw new JobNotFoundException();
        List<UnitSnapshot> units = lockAllJobUnits(connection, jobId);
        CancelContext context = lockAccessibleJob(connection, actorUserId, jobId);

        CancelResult replay = findReplay(connection, jobId, deduplicationKey, context.jobStatus());
        if (replay != null) {
          connection.commit();
          return replay;
        }
        if (!"fake-local".equals(context.providerId())) throw new CancelNotAllowedException();
        if (!CANCELLABLE_JOB_STATUSES.contains(context.jobStatus())) {
          throw new CancelNotAllowedException();
        }

        List<UUID> cancellableUnitIds =
            units.stream()
                .filter(unit -> CANCELLABLE_UNIT_STATUSES.contains(unit.status()))
                .map(UnitSnapshot::id)
                .toList();
        List<UUID> cancelledAttemptIds = cancelRunningAttempts(connection, jobId);
        cancelUnits(connection, jobId, units);
        updateJobAsCancelled(connection, jobId);
        UUID eventId = UUID.randomUUID();
        insertCancelRequestedEvent(
            connection, eventId, jobId, cancellableUnitIds, cancelledAttemptIds, deduplicationKey);
        connection.commit();
        return new CancelResult(
            jobId,
            "cancelled",
            cancellableUnitIds.size(),
            cancelledAttemptIds.size(),
            false,
            eventId);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (JobNotFoundException | CancelNotAllowedException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to cancel translation job", exception);
    }
  }

  private static boolean hasWritableAccess(Connection connection, UUID actorUserId, UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1
                FROM translation_jobs job
                JOIN works work ON work.id = job.work_id
                JOIN libraries library ON library.id = work.library_id
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                JOIN chapters chapter ON chapter.id = job.chapter_id AND chapter.work_id = job.work_id
                WHERE job.id = ? AND work.deleted_at IS NULL AND library.deleted_at IS NULL
                  AND can_access_library(library.id, actor.id, TRUE)
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, jobId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static List<UnitSnapshot> lockAllJobUnits(Connection connection, UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id, status FROM translation_units WHERE job_id = ? ORDER BY unit_no FOR UPDATE")) {
      select.setObject(1, jobId);
      try (ResultSet rows = select.executeQuery()) {
        List<UnitSnapshot> units = new ArrayList<>();
        while (rows.next()) {
          units.add(new UnitSnapshot(rows.getObject("id", UUID.class), rows.getString("status")));
        }
        return List.copyOf(units);
      }
    }
  }

  private static CancelContext lockAccessibleJob(
      Connection connection, UUID actorUserId, UUID jobId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT job.provider_id, job.status
                FROM translation_jobs job
                JOIN works work ON work.id = job.work_id
                JOIN libraries library ON library.id = work.library_id
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                JOIN chapters chapter ON chapter.id = job.chapter_id AND chapter.work_id = job.work_id
                WHERE job.id = ? AND work.deleted_at IS NULL AND library.deleted_at IS NULL
                  AND can_access_library(library.id, actor.id, TRUE)
                FOR UPDATE OF job
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new JobNotFoundException();
        return new CancelContext(row.getString("provider_id"), row.getString("status"));
      }
    }
  }

  private static CancelResult findReplay(
      Connection connection, UUID jobId, String deduplicationKey, String currentStatus)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event_id, jsonb_array_length(payload->'unitIds') AS unit_count,
                       jsonb_array_length(payload->'attemptIds') AS attempt_count
                FROM translation_outbox_events
                WHERE aggregate_id = ? AND event_type = 'translation.job.cancel_requested'
                  AND deduplication_key = ?
                """)) {
      select.setObject(1, jobId);
      select.setString(2, deduplicationKey);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? new CancelResult(
                jobId,
                currentStatus,
                row.getInt("unit_count"),
                row.getInt("attempt_count"),
                true,
                row.getObject("event_id", UUID.class))
            : null;
      }
    }
  }

  private static List<UUID> cancelRunningAttempts(Connection connection, UUID jobId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_attempts attempt
                SET outcome = 'cancelled', failure_code = 'CANCELLED',
                    duration_ms = GREATEST(0, floor(EXTRACT(EPOCH FROM
                        (clock_timestamp() - attempt.started_at)) * 1000)::bigint),
                    completed_at = clock_timestamp()
                FROM translation_units unit
                WHERE attempt.unit_id = unit.id AND unit.job_id = ? AND unit.status = 'running'
                  AND attempt.outcome = 'running' AND attempt.completed_at IS NULL
                RETURNING attempt.id
                """)) {
      update.setObject(1, jobId);
      try (ResultSet rows = update.executeQuery()) {
        List<UUID> attemptIds = new ArrayList<>();
        while (rows.next()) attemptIds.add(rows.getObject("id", UUID.class));
        return List.copyOf(attemptIds);
      }
    }
  }

  private static void cancelUnits(Connection connection, UUID jobId, List<UnitSnapshot> units)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_units
                SET status = 'cancelled', lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, heartbeat_at = NULL, updated_at = clock_timestamp()
                WHERE id = ? AND job_id = ? AND status = ?
                """)) {
      for (UnitSnapshot unit : units) {
        if (!CANCELLABLE_UNIT_STATUSES.contains(unit.status())) continue;
        update.setObject(1, unit.id());
        update.setObject(2, jobId);
        update.setString(3, unit.status());
        if (update.executeUpdate() != 1) throw new CancelConflictException();
      }
    }
  }

  private static void updateJobAsCancelled(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_jobs job
                SET status = 'cancelled',
                    completed_units = counts.completed_units,
                    failed_units = counts.failed_units,
                    failure_code = NULL,
                    cancel_requested_at = COALESCE(job.cancel_requested_at, clock_timestamp()),
                    completed_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                FROM (
                    SELECT count(*) FILTER (WHERE status = 'succeeded')::integer AS completed_units,
                           count(*) FILTER (WHERE status = 'failed')::integer AS failed_units
                    FROM translation_units WHERE job_id = ?
                ) counts
                WHERE job.id = ?
                  AND job.status IN ('queued', 'preparing', 'translating', 'retry_wait', 'cancel_requested')
                """)) {
      update.setObject(1, jobId);
      update.setObject(2, jobId);
      if (update.executeUpdate() != 1) throw new CancelConflictException();
    }
  }

  private static void insertCancelRequestedEvent(
      Connection connection,
      UUID eventId,
      UUID jobId,
      List<UUID> unitIds,
      List<UUID> attemptIds,
      String deduplicationKey)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events
                    (event_id, aggregate_id, event_type, schema_version, deduplication_key,
                     correlation_id, payload)
                VALUES (?, ?, 'translation.job.cancel_requested', 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'unitIds', ?::jsonb,
                                           'attemptIds', ?::jsonb))
                """)) {
      insert.setObject(1, eventId);
      insert.setObject(2, jobId);
      insert.setString(3, deduplicationKey);
      insert.setObject(4, eventId);
      insert.setObject(5, jobId);
      insert.setString(6, jsonUuidArray(unitIds));
      insert.setString(7, jsonUuidArray(attemptIds));
      if (insert.executeUpdate() != 1) throw new CancelConflictException();
    }
  }

  private static String jsonUuidArray(List<UUID> ids) {
    return "["
        + ids.stream()
            .map(id -> "\"" + id + "\"")
            .reduce((left, right) -> left + "," + right)
            .orElse("")
        + "]";
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record UnitSnapshot(UUID id, String status) {}

  private record CancelContext(String providerId, String jobStatus) {}

  public record CancelResult(
      UUID jobId,
      String jobStatus,
      int cancelledUnits,
      int cancelledAttempts,
      boolean replayed,
      UUID outboxEventId) {}

  public static final class JobNotFoundException extends RuntimeException {}

  public static final class CancelNotAllowedException extends RuntimeException {}

  public static final class CancelConflictException extends RuntimeException {}
}
