package com.novelweb.translation.jobs;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Authorizes and requeues eligible failed fake-local units with one durable receipt. */
@Repository
@Profile("postgres")
public class TranslationJobRetryRepository {

  private final DataSource dataSource;

  public TranslationJobRetryRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public RetryResult retryFailedUnits(UUID actorUserId, UUID jobId, String deduplicationKey) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        RetryContext context = lockAccessibleJob(connection, actorUserId, jobId);
        if (!"fake-local".equals(context.providerId())) throw new RetryNotAllowedException();

        RetryResult replay = findReplay(connection, jobId, deduplicationKey, context.jobStatus());
        if (replay != null) {
          connection.commit();
          return replay;
        }
        if (!"ready".equals(context.chapterStatus())
            || !context.sourceRevisionId().equals(context.currentRevisionId())
            || !MessageDigest.isEqual(context.sourceSha256(), context.currentSourceSha256())) {
          throw new SourceRevisionStaleException();
        }
        if (!"failed".equals(context.jobStatus())) throw new RetryNotAllowedException();

        List<UUID> eligibleUnitIds = lockEligibleFailedUnits(connection, jobId);
        if (eligibleUnitIds.isEmpty()) throw new NoRetryableUnitsException();
        requeueUnits(connection, jobId, eligibleUnitIds);
        updateJobForRetry(connection, jobId);
        UUID eventId = UUID.randomUUID();
        insertRetryRequestedEvent(
            connection,
            eventId,
            jobId,
            context.sourceRevisionId(),
            eligibleUnitIds,
            deduplicationKey);
        connection.commit();
        return new RetryResult(jobId, "queued", eligibleUnitIds.size(), false, eventId);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (JobNotFoundException
        | SourceRevisionStaleException
        | RetryNotAllowedException
        | NoRetryableUnitsException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to retry failed translation units", exception);
    }
  }

  private static RetryContext lockAccessibleJob(Connection connection, UUID actorUserId, UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT job.source_revision_id, job.source_sha256, job.provider_id, job.status,
                       chapter.status AS chapter_status, chapter.source_revision_id AS current_revision_id,
                       revision.content_hash AS current_source_sha256
                FROM translation_jobs job
                JOIN works work ON work.id = job.work_id
                JOIN libraries library ON library.id = work.library_id
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                JOIN chapters chapter ON chapter.id = job.chapter_id AND chapter.work_id = job.work_id
                JOIN chapter_revisions revision
                  ON revision.chapter_id = chapter.id AND revision.id = chapter.source_revision_id
                WHERE job.id = ? AND work.deleted_at IS NULL AND library.deleted_at IS NULL
                  AND can_access_library(library.id, actor.id, TRUE)
                FOR UPDATE OF job, chapter
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new JobNotFoundException();
        return new RetryContext(
            row.getObject("source_revision_id", UUID.class),
            row.getBytes("source_sha256"),
            row.getString("provider_id"),
            row.getString("status"),
            row.getString("chapter_status"),
            row.getObject("current_revision_id", UUID.class),
            row.getBytes("current_source_sha256"));
      }
    }
  }

  private static RetryResult findReplay(
      Connection connection, UUID jobId, String deduplicationKey, String currentStatus)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event_id, jsonb_array_length(payload->'unitIds') AS unit_count
                FROM translation_outbox_events
                WHERE aggregate_id = ? AND event_type = 'translation.job.retry_requested'
                  AND deduplication_key = ?
                """)) {
      select.setObject(1, jobId);
      select.setString(2, deduplicationKey);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? new RetryResult(
                jobId,
                currentStatus,
                row.getInt("unit_count"),
                true,
                row.getObject("event_id", UUID.class))
            : null;
      }
    }
  }

  private static List<UUID> lockEligibleFailedUnits(Connection connection, UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id FROM translation_units
                WHERE job_id = ? AND status = 'failed' AND attempt_count < max_attempts
                ORDER BY unit_no FOR UPDATE
                """)) {
      select.setObject(1, jobId);
      try (ResultSet rows = select.executeQuery()) {
        List<UUID> unitIds = new ArrayList<>();
        while (rows.next()) unitIds.add(rows.getObject("id", UUID.class));
        return List.copyOf(unitIds);
      }
    }
  }

  private static void requeueUnits(Connection connection, UUID jobId, List<UUID> unitIds)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_units
                SET status = 'queued', available_at = clock_timestamp(), last_failure_code = NULL,
                    updated_at = clock_timestamp()
                WHERE job_id = ? AND id = ? AND status = 'failed'
                  AND attempt_count < max_attempts
                """)) {
      for (UUID unitId : unitIds) {
        update.setObject(1, jobId);
        update.setObject(2, unitId);
        update.addBatch();
      }
      int[] updated = update.executeBatch();
      for (int count : updated) {
        if (count != 1 && count != PreparedStatement.SUCCESS_NO_INFO) {
          throw new RetryConflictException();
        }
      }
    }
  }

  private static void updateJobForRetry(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_jobs job
                SET status = 'queued', completed_units = counts.completed_units,
                    failed_units = counts.failed_units, failure_code = NULL,
                    completed_at = NULL, updated_at = clock_timestamp()
                FROM (
                    SELECT count(*) FILTER (WHERE status = 'succeeded')::integer AS completed_units,
                           count(*) FILTER (WHERE status = 'failed')::integer AS failed_units
                    FROM translation_units WHERE job_id = ?
                ) counts
                WHERE job.id = ? AND job.status = 'failed'
                """)) {
      update.setObject(1, jobId);
      update.setObject(2, jobId);
      if (update.executeUpdate() != 1) throw new RetryConflictException();
    }
  }

  private static void insertRetryRequestedEvent(
      Connection connection,
      UUID eventId,
      UUID jobId,
      UUID sourceRevisionId,
      List<UUID> unitIds,
      String deduplicationKey)
      throws SQLException {
    String unitIdJson =
        "["
            + unitIds.stream()
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow()
            + "]";
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events
                    (event_id, aggregate_id, event_type, schema_version, deduplication_key,
                     correlation_id, payload)
                VALUES (?, ?, 'translation.job.retry_requested', 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'sourceRevisionId', ?::text,
                                           'unitIds', ?::jsonb))
                """)) {
      insert.setObject(1, eventId);
      insert.setObject(2, jobId);
      insert.setString(3, deduplicationKey);
      insert.setObject(4, eventId);
      insert.setObject(5, jobId);
      insert.setObject(6, sourceRevisionId);
      insert.setString(7, unitIdJson);
      if (insert.executeUpdate() != 1) throw new RetryConflictException();
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record RetryContext(
      UUID sourceRevisionId,
      byte[] sourceSha256,
      String providerId,
      String jobStatus,
      String chapterStatus,
      UUID currentRevisionId,
      byte[] currentSourceSha256) {}

  public record RetryResult(
      UUID jobId, String jobStatus, int retriedUnits, boolean replayed, UUID outboxEventId) {}

  public static final class JobNotFoundException extends RuntimeException {}

  public static final class SourceRevisionStaleException extends RuntimeException {}

  public static final class RetryNotAllowedException extends RuntimeException {}

  public static final class NoRetryableUnitsException extends RuntimeException {}

  public static final class RetryConflictException extends RuntimeException {}
}
