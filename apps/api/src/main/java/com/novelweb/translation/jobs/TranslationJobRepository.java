package com.novelweb.translation.jobs;

import java.security.MessageDigest;
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
import org.springframework.stereotype.Repository;

/** Persists translation job intent and its wake-up event in one PostgreSQL transaction. */
@Repository
@Profile("postgres")
public class TranslationJobRepository {

  private final DataSource dataSource;
  private final TranslationChunkPlanner chunkPlanner;

  public TranslationJobRepository(DataSource dataSource, TranslationChunkPlanner chunkPlanner) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.chunkPlanner = Objects.requireNonNull(chunkPlanner, "chunk planner is required");
  }

  public TranslationJobService.EnqueueResult enqueue(EnqueueCommand command) {
    Objects.requireNonNull(command, "enqueue command is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        CurrentChapter chapter =
            lockAccessibleCurrentChapter(connection, command.actorUserId(), command.chapterId())
                .orElseThrow(ChapterNotFoundException::new);

        Optional<Job> prior =
            findByIdempotencyKey(
                connection, command.actorUserId(), command.chapterId(), command.idempotencyKey());
        if (prior.isPresent()) {
          Job existing = prior.orElseThrow();
          requireSameRequest(existing.requestSha256(), command.requestSha256());
          connection.commit();
          return new TranslationJobService.EnqueueResult(existing, true);
        }

        if (!chapter.sourceRevisionId().equals(command.sourceRevisionId())) {
          throw new SourceRevisionStaleException();
        }
        if (chapter.sourceLanguage().equalsIgnoreCase(command.targetLanguage())) {
          throw new UnsupportedLanguagePairException();
        }
        List<TranslationChunkPlanner.UnitPlan> units =
            chunkPlanner.plan(
                chapter.sourceLanguage(),
                command.targetLanguage(),
                loadTranslatableSegments(connection, command.sourceRevisionId()));

        UUID jobId = UUID.randomUUID();
        UUID outboxEventId = UUID.randomUUID();
        int inserted = insertJob(connection, command, chapter, jobId);
        if (inserted == 0) {
          Job raced =
              findByIdempotencyKey(
                      connection,
                      command.actorUserId(),
                      command.chapterId(),
                      command.idempotencyKey())
                  .orElseThrow(() -> new SQLException("Idempotency claim disappeared"));
          requireSameRequest(raced.requestSha256(), command.requestSha256());
          connection.commit();
          return new TranslationJobService.EnqueueResult(raced, true);
        }

        insertUnits(connection, jobId, command.sourceRevisionId(), units);
        markJobQueued(connection, jobId, units.size());
        insertRequestedEvent(connection, outboxEventId, jobId, chapter.sourceRevisionId());
        Job created = findById(connection, jobId).orElseThrow();
        connection.commit();
        return new TranslationJobService.EnqueueResult(created, false);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (ChapterNotFoundException
        | SourceRevisionStaleException
        | UnsupportedLanguagePairException
        | IdempotencyKeyReusedException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to enqueue translation job", exception);
    }
  }

  private static List<TranslationChunkPlanner.SourceSegment> loadTranslatableSegments(
      Connection connection, UUID sourceRevisionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, ordinal, segment_type, stable_anchor, source_text, checksum,
                       octet_length(source_text) AS source_bytes
                FROM segments
                WHERE chapter_revision_id = ?
                  AND segment_type IN ('paragraph', 'heading', 'quote')
                ORDER BY ordinal ASC
                LIMIT 5001
                """)) {
      select.setObject(1, sourceRevisionId);
      select.setFetchSize(32);
      try (ResultSet rows = select.executeQuery()) {
        List<TranslationChunkPlanner.SourceSegment> segments = new ArrayList<>();
        long totalSourceBytes = 0;
        while (rows.next()) {
          if (segments.size() >= TranslationChunkPlanner.MAX_JOB_SEGMENTS) {
            throw new TranslationChunkPlanner.InputLimitExceededException("segment_count");
          }
          int sourceBytes = rows.getInt("source_bytes");
          if (sourceBytes > TranslationChunkPlanner.MAX_JOB_SOURCE_BYTES) {
            throw new TranslationChunkPlanner.InputLimitExceededException("source_bytes");
          }
          totalSourceBytes += sourceBytes;
          if (totalSourceBytes > TranslationChunkPlanner.MAX_JOB_SOURCE_BYTES) {
            throw new TranslationChunkPlanner.InputLimitExceededException("source_bytes");
          }
          String sourceText = rows.getString("source_text");
          segments.add(
              new TranslationChunkPlanner.SourceSegment(
                  rows.getObject("id", UUID.class),
                  rows.getInt("ordinal"),
                  rows.getString("segment_type"),
                  rows.getString("stable_anchor"),
                  sourceText,
                  rows.getBytes("checksum")));
        }
        return List.copyOf(segments);
      }
    }
  }

  private static void insertUnits(
      Connection connection,
      UUID jobId,
      UUID sourceRevisionId,
      List<TranslationChunkPlanner.UnitPlan> units)
      throws SQLException {
    try (PreparedStatement insertUnit =
            connection.prepareStatement(
                """
                    INSERT INTO translation_units
                        (id, job_id, source_revision_id, unit_no, idempotency_key)
                    VALUES (?, ?, ?, ?, ?)
                    """);
        PreparedStatement insertSegment =
            connection.prepareStatement(
                """
                    INSERT INTO translation_unit_segments
                        (job_id, unit_id, source_revision_id, source_segment_id,
                         segment_ordinal, source_sha256)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
      for (TranslationChunkPlanner.UnitPlan unit : units) {
        UUID unitId = UUID.randomUUID();
        insertUnit.setObject(1, unitId);
        insertUnit.setObject(2, jobId);
        insertUnit.setObject(3, sourceRevisionId);
        insertUnit.setInt(4, unit.unitNo());
        insertUnit.setObject(5, unitId);
        insertUnit.executeUpdate();

        for (int index = 0; index < unit.segments().size(); index++) {
          TranslationChunkPlanner.SourceSegment segment = unit.segments().get(index);
          insertSegment.setObject(1, jobId);
          insertSegment.setObject(2, unitId);
          insertSegment.setObject(3, sourceRevisionId);
          insertSegment.setObject(4, segment.id());
          insertSegment.setInt(5, index + 1);
          insertSegment.setBytes(6, segment.checksum());
          insertSegment.executeUpdate();
        }
      }
    }
  }

  private static void markJobQueued(Connection connection, UUID jobId, int totalUnits)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_jobs
                SET status = 'queued', total_units = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'preparing'
                """)) {
      update.setInt(1, totalUnits);
      update.setObject(2, jobId);
      if (update.executeUpdate() != 1) {
        throw new SQLException("Translation job could not be moved to queued");
      }
    }
  }

  private static Optional<CurrentChapter> lockAccessibleCurrentChapter(
      Connection connection, UUID actorUserId, UUID chapterId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT chapter.work_id, chapter.source_language, chapter.source_revision_id,
                       revision.content_hash
                FROM chapters chapter
                JOIN works work ON work.id = chapter.work_id
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                JOIN chapter_revisions revision
                  ON revision.chapter_id = chapter.id
                 AND revision.id = chapter.source_revision_id
                WHERE chapter.id = ? AND chapter.status = 'ready'
                  AND chapter.deleted_at IS NULL AND work.deleted_at IS NULL
                  AND can_access_library(work.library_id, actor.id, FALSE)
                FOR SHARE OF chapter
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, chapterId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new CurrentChapter(
                row.getObject("work_id", UUID.class),
                row.getString("source_language"),
                row.getObject("source_revision_id", UUID.class),
                row.getBytes("content_hash")));
      }
    }
  }

  private static Optional<Job> findByIdempotencyKey(
      Connection connection, UUID actorUserId, UUID chapterId, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, requested_by_user_id, work_id, chapter_id, source_revision_id,
                       source_language, target_language, provider_id, source_sha256, request_sha256,
                       status, total_units, created_at
                FROM translation_jobs
                WHERE requested_by_user_id = ? AND chapter_id = ? AND idempotency_key = ?
                FOR UPDATE
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, chapterId);
      select.setString(3, idempotencyKey);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(job(row)) : Optional.empty();
      }
    }
  }

  private static int insertJob(
      Connection connection, EnqueueCommand command, CurrentChapter chapter, UUID jobId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_jobs (
                    id, requested_by_user_id, work_id, chapter_id, source_revision_id,
                    source_language, target_language, provider_id, profile_snapshot,
                    glossary_snapshot, source_sha256, request_sha256, idempotency_key,
                    status, total_units)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                        '{"mode":"fake-local","schemaVersion":1}'::jsonb, '[]'::jsonb,
                        ?, ?, ?, 'preparing', 0)
                ON CONFLICT (requested_by_user_id, chapter_id, idempotency_key) DO NOTHING
                """)) {
      insert.setObject(1, jobId);
      insert.setObject(2, command.actorUserId());
      insert.setObject(3, chapter.workId());
      insert.setObject(4, command.chapterId());
      insert.setObject(5, command.sourceRevisionId());
      insert.setString(6, chapter.sourceLanguage());
      insert.setString(7, command.targetLanguage());
      insert.setString(8, command.providerId());
      insert.setBytes(9, chapter.sourceSha256());
      insert.setBytes(10, command.requestSha256());
      insert.setString(11, command.idempotencyKey());
      return insert.executeUpdate();
    }
  }

  private static void insertRequestedEvent(
      Connection connection, UUID eventId, UUID jobId, UUID sourceRevisionId) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events (
                    event_id, aggregate_id, event_type, schema_version, deduplication_key,
                    correlation_id, payload)
                VALUES (?, ?, 'translation.job.requested', 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'sourceRevisionId', ?::text))
                """)) {
      insert.setObject(1, eventId);
      insert.setObject(2, jobId);
      insert.setString(3, "job-requested-" + jobId);
      insert.setObject(4, jobId);
      insert.setObject(5, jobId);
      insert.setObject(6, sourceRevisionId);
      insert.executeUpdate();
    }
  }

  private static Optional<Job> findById(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, requested_by_user_id, work_id, chapter_id, source_revision_id,
                       source_language, target_language, provider_id, source_sha256, request_sha256,
                       status, total_units, created_at
                FROM translation_jobs WHERE id = ?
                """)) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(job(row)) : Optional.empty();
      }
    }
  }

  private static Job job(ResultSet row) throws SQLException {
    return new Job(
        row.getObject("id", UUID.class),
        row.getObject("requested_by_user_id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getObject("source_revision_id", UUID.class),
        row.getString("source_language"),
        row.getString("target_language"),
        row.getString("provider_id"),
        row.getBytes("source_sha256"),
        row.getBytes("request_sha256"),
        row.getString("status"),
        row.getInt("total_units"),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static void requireSameRequest(byte[] existingHash, byte[] requestedHash) {
    if (!MessageDigest.isEqual(existingHash, requestedHash)) {
      throw new IdempotencyKeyReusedException();
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      exception.addSuppressed(rollbackFailure);
    }
  }

  public record EnqueueCommand(
      UUID actorUserId,
      UUID chapterId,
      UUID sourceRevisionId,
      String targetLanguage,
      String providerId,
      String idempotencyKey,
      byte[] requestSha256) {
    public EnqueueCommand {
      Objects.requireNonNull(actorUserId);
      Objects.requireNonNull(chapterId);
      Objects.requireNonNull(sourceRevisionId);
      Objects.requireNonNull(targetLanguage);
      Objects.requireNonNull(providerId);
      Objects.requireNonNull(idempotencyKey);
      if (requestSha256 == null || requestSha256.length != 32) {
        throw new IllegalArgumentException("request hash must be SHA-256");
      }
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }
  }

  public record Job(
      UUID id,
      UUID requestedByUserId,
      UUID workId,
      UUID chapterId,
      UUID sourceRevisionId,
      String sourceLanguage,
      String targetLanguage,
      String providerId,
      byte[] sourceSha256,
      byte[] requestSha256,
      String status,
      int totalUnits,
      Instant createdAt) {
    public Job {
      sourceSha256 = sourceSha256.clone();
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] sourceSha256() {
      return sourceSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }
  }

  private record CurrentChapter(
      UUID workId, String sourceLanguage, UUID sourceRevisionId, byte[] sourceSha256) {
    private CurrentChapter {
      sourceSha256 = sourceSha256.clone();
    }

    @Override
    public byte[] sourceSha256() {
      return sourceSha256.clone();
    }
  }

  public static final class ChapterNotFoundException extends RuntimeException {}

  public static final class SourceRevisionStaleException extends RuntimeException {}

  public static final class UnsupportedLanguagePairException extends RuntimeException {}

  public static final class IdempotencyKeyReusedException extends RuntimeException {}
}
