package com.novelweb.translation.jobs;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Computes writer readiness and atomically freezes a complete manual-edit snapshot. */
@Repository
@Profile("postgres")
public class TranslationCandidateReviewRepository {

  private final DataSource dataSource;

  public TranslationCandidateReviewRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Readiness readReadiness(UUID actorUserId, UUID jobId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        Context context = loadContext(connection, actorUserId, jobId, false).orElse(null);
        if (context == null) {
          connection.commit();
          return Readiness.notFound();
        }
        if (!context.sourceCurrent()) {
          connection.commit();
          return Readiness.staleSource();
        }
        if (!context.candidateReady()) {
          connection.commit();
          return Readiness.candidateNotReady();
        }
        List<SnapshotSegment> segments = readSnapshotSegments(connection, context);
        int totalSegments = segments.size();
        List<Integer> missingOrdinals =
            segments.stream()
                .filter(segment -> !segment.hasManualEdit())
                .map(SnapshotSegment::ordinal)
                .toList();
        int editedSegments = totalSegments - missingOrdinals.size();
        byte[] snapshotDigest =
            missingOrdinals.isEmpty()
                ? TranslationReviewSnapshotHasher.snapshotDigest(context.hashContext(), segments)
                : null;
        boolean pending = pendingSubmissionExists(connection, context.candidateId());
        connection.commit();
        return Readiness.available(
            context, editedSegments, totalSegments, missingOrdinals, pending, snapshotDigest);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to compute manual translation readiness", exception);
    }
  }

  public SubmissionResult submit(
      UUID actorUserId, UUID jobId, String expectedEtag, byte[] idempotencyKeySha256) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    Objects.requireNonNull(expectedEtag, "expected snapshot ETag is required");
    Objects.requireNonNull(idempotencyKeySha256, "idempotency digest is required");

    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        Context context = loadContext(connection, actorUserId, jobId, true).orElse(null);
        if (context == null) {
          connection.commit();
          return SubmissionResult.notFound();
        }

        byte[] requestSha256 =
            TranslationReviewSnapshotHasher.requestDigest(
                context.candidateId(), jobId, expectedEtag);
        Optional<SubmissionReceipt> prior =
            findReplay(
                connection,
                actorUserId,
                context.candidateId(),
                idempotencyKeySha256,
                requestSha256);
        if (prior.isPresent()) {
          connection.commit();
          return SubmissionResult.replayed(prior.orElseThrow());
        }

        if (!context.sourceCurrent()) {
          connection.commit();
          return SubmissionResult.staleSource();
        }
        if (!context.candidateReady()) {
          connection.commit();
          return SubmissionResult.candidateNotReady();
        }

        List<SnapshotSegment> segments = readSnapshotSegments(connection, context);
        int editedSegments = (int) segments.stream().filter(SnapshotSegment::hasManualEdit).count();
        if (editedSegments != segments.size()) {
          connection.commit();
          return SubmissionResult.incomplete(editedSegments, segments.size());
        }

        byte[] snapshotDigest =
            TranslationReviewSnapshotHasher.snapshotDigest(context.hashContext(), segments);
        String currentEtag = TranslationReviewSnapshotHasher.etag(snapshotDigest);
        if (!MessageDigest.isEqual(
            currentEtag.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            expectedEtag.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
          connection.commit();
          return SubmissionResult.staleEtag(currentEtag);
        }
        if (pendingSubmissionExists(connection, context.candidateId())) {
          connection.commit();
          return SubmissionResult.pendingConflict();
        }

        SubmissionReceipt receipt =
            insertSubmission(
                connection,
                actorUserId,
                context,
                segments,
                snapshotDigest,
                idempotencyKeySha256,
                requestSha256);
        connection.commit();
        return SubmissionResult.created(receipt);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) {
        throw new SubmissionConflictException();
      }
      throw new IllegalStateException(
          "Unable to submit the manual translation snapshot", exception);
    }
  }

  private static Optional<Context> loadContext(
      Connection connection, UUID actorUserId, UUID jobId, boolean lock) throws SQLException {
    String sql =
        """
            SELECT job.id AS job_id, job.work_id, job.chapter_id, job.source_revision_id,
                   job.source_language, job.target_language, job.source_sha256,
                   candidate.id AS candidate_id,
                   (chapter.status = 'ready' AND chapter.source_revision_id = job.source_revision_id
                     AND revision.content_hash = job.source_sha256
                     AND candidate.source_sha256 = job.source_sha256) AS source_current,
                   (job.provider_id = 'fake-local' AND job.status = 'review_required'
                     AND candidate.status = 'ready_for_review'
                     AND (SELECT count(*) FROM translation_candidate_segments candidate_segment
                          WHERE candidate_segment.candidate_id = candidate.id)
                       = (SELECT count(*) FROM translation_unit_segments unit_segment
                          WHERE unit_segment.job_id = job.id
                            AND unit_segment.source_revision_id = job.source_revision_id)
                     AND NOT EXISTS (
                         SELECT 1 FROM translation_unit_segments unit_segment
                         WHERE unit_segment.job_id = job.id
                           AND unit_segment.source_revision_id = job.source_revision_id
                           AND NOT EXISTS (
                               SELECT 1 FROM translation_candidate_segments candidate_segment
                               WHERE candidate_segment.candidate_id = candidate.id
                                 AND candidate_segment.source_segment_id = unit_segment.source_segment_id))
                     AND NOT EXISTS (
                         SELECT 1 FROM translation_candidate_segments candidate_segment
                         WHERE candidate_segment.candidate_id = candidate.id
                           AND NOT EXISTS (
                               SELECT 1 FROM translation_unit_segments unit_segment
                               WHERE unit_segment.job_id = job.id
                                 AND unit_segment.source_revision_id = job.source_revision_id
                                 AND unit_segment.source_segment_id = candidate_segment.source_segment_id)))
                     AS candidate_ready
            FROM translation_jobs job
            JOIN works work ON work.id = job.work_id
            JOIN libraries library ON library.id = work.library_id
            JOIN chapters chapter ON chapter.id = job.chapter_id AND chapter.work_id = job.work_id
            JOIN chapter_revisions revision
              ON revision.id = job.source_revision_id AND revision.chapter_id = job.chapter_id
            JOIN users actor ON actor.id = ? AND actor.status = 'active'
            JOIN LATERAL (
                SELECT id, status, source_sha256 FROM translation_candidates
                WHERE job_id = job.id ORDER BY candidate_no DESC LIMIT 1
            ) candidate ON TRUE
            WHERE job.id = ? AND work.deleted_at IS NULL AND library.deleted_at IS NULL
              AND can_access_library(library.id, actor.id, TRUE)
            """
            + (lock ? " FOR UPDATE OF actor, job, chapter, candidate" : "");
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, actorUserId);
      select.setObject(2, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new Context(
                row.getObject("candidate_id", UUID.class),
                row.getObject("job_id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getObject("chapter_id", UUID.class),
                row.getObject("source_revision_id", UUID.class),
                row.getString("source_language"),
                row.getString("target_language"),
                row.getBytes("source_sha256"),
                row.getBoolean("source_current"),
                row.getBoolean("candidate_ready")));
      }
    }
  }

  private static List<SnapshotSegment> readSnapshotSegments(Connection connection, Context context)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT unit_segment.source_segment_id, unit_segment.segment_ordinal,
                       latest_edit.edit_revision, latest_edit.translated_text,
                       latest_edit.translated_sha256
                FROM translation_unit_segments unit_segment
                JOIN translation_candidate_segments candidate_segment
                  ON candidate_segment.candidate_id = ?
                 AND candidate_segment.source_segment_id = unit_segment.source_segment_id
                LEFT JOIN LATERAL (
                    SELECT edit.edit_revision, edit.translated_text, edit.translated_sha256
                    FROM translation_candidate_segment_edits edit
                    WHERE edit.candidate_id = candidate_segment.candidate_id
                      AND edit.source_segment_id = candidate_segment.source_segment_id
                    ORDER BY edit.edit_revision DESC LIMIT 1
                ) latest_edit ON TRUE
                WHERE unit_segment.job_id = ? AND unit_segment.source_revision_id = ?
                ORDER BY unit_segment.segment_ordinal, unit_segment.source_segment_id
                """)) {
      select.setObject(1, context.candidateId());
      select.setObject(2, context.jobId());
      select.setObject(3, context.sourceRevisionId());
      try (ResultSet rows = select.executeQuery()) {
        List<SnapshotSegment> segments = new ArrayList<>();
        while (rows.next()) {
          int editRevision = rows.getInt("edit_revision");
          boolean hasEdit = !rows.wasNull();
          byte[] translatedSha256 = hasEdit ? rows.getBytes("translated_sha256") : null;
          if (hasEdit
              && !MessageDigest.isEqual(
                  translatedSha256, sha256(rows.getString("translated_text")))) {
            throw new IllegalStateException(
                "Manual translation edit digest does not match its text");
          }
          segments.add(
              new SnapshotSegment(
                  rows.getObject("source_segment_id", UUID.class),
                  rows.getInt("segment_ordinal"),
                  hasEdit ? editRevision : null,
                  translatedSha256,
                  hasEdit && !rows.getString("translated_text").isBlank()));
        }
        return List.copyOf(segments);
      }
    }
  }

  private static Optional<SubmissionReceipt> findReplay(
      Connection connection,
      UUID actorUserId,
      UUID candidateId,
      byte[] idempotencyKeySha256,
      byte[] requestSha256)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, snapshot_sha256, segment_count, submitted_at, request_sha256, status
                FROM translation_candidate_review_submissions
                WHERE submitted_by_user_id = ? AND candidate_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, candidateId);
      select.setBytes(3, idempotencyKeySha256);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        if (!MessageDigest.isEqual(requestSha256, row.getBytes("request_sha256"))) {
          throw new IdempotencyKeyReusedException();
        }
        return Optional.of(
            new SubmissionReceipt(
                row.getObject("id", UUID.class),
                candidateId,
                row.getString("status"),
                HexFormat.of().formatHex(row.getBytes("snapshot_sha256")),
                row.getInt("segment_count"),
                row.getObject("submitted_at", java.time.OffsetDateTime.class).toInstant(),
                true));
      }
    }
  }

  private static boolean pendingSubmissionExists(Connection connection, UUID candidateId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM translation_candidate_review_submissions "
                + "WHERE candidate_id = ? AND status = 'pending_review')")) {
      select.setObject(1, candidateId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getBoolean(1);
      }
    }
  }

  private static SubmissionReceipt insertSubmission(
      Connection connection,
      UUID actorUserId,
      Context context,
      List<SnapshotSegment> segments,
      byte[] snapshotDigest,
      byte[] idempotencyKeySha256,
      byte[] requestSha256)
      throws SQLException {
    UUID submissionId = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_candidate_review_submissions
                    (id, candidate_id, job_id, work_id, chapter_id, target_language,
                     source_revision_id, source_sha256, snapshot_sha256, segment_count,
                     submitted_by_user_id, idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING submitted_at
                """)) {
      insert.setObject(1, submissionId);
      insert.setObject(2, context.candidateId());
      insert.setObject(3, context.jobId());
      insert.setObject(4, context.workId());
      insert.setObject(5, context.chapterId());
      insert.setString(6, context.targetLanguage());
      insert.setObject(7, context.sourceRevisionId());
      insert.setBytes(8, context.sourceSha256());
      insert.setBytes(9, snapshotDigest);
      insert.setInt(10, segments.size());
      insert.setObject(11, actorUserId);
      insert.setBytes(12, idempotencyKeySha256);
      insert.setBytes(13, requestSha256);
      Instant submittedAt;
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        submittedAt = row.getObject("submitted_at", java.time.OffsetDateTime.class).toInstant();
      }

      try (PreparedStatement insertSegment =
          connection.prepareStatement(
              """
                  INSERT INTO translation_candidate_review_submission_segments
                      (submission_id, candidate_id, source_segment_id, segment_ordinal,
                       edit_revision, translated_sha256)
                  VALUES (?, ?, ?, ?, ?, ?)
                  """)) {
        for (SnapshotSegment segment : segments) {
          insertSegment.setObject(1, submissionId);
          insertSegment.setObject(2, context.candidateId());
          insertSegment.setObject(3, segment.sourceSegmentId());
          insertSegment.setInt(4, segment.ordinal());
          insertSegment.setInt(5, segment.editRevision());
          insertSegment.setBytes(6, segment.translatedSha256());
          insertSegment.addBatch();
        }
        insertSegment.executeBatch();
      }
      return new SubmissionReceipt(
          submissionId,
          context.candidateId(),
          "pending_review",
          HexFormat.of().formatHex(snapshotDigest),
          segments.size(),
          submittedAt,
          false);
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record Context(
      UUID candidateId,
      UUID jobId,
      UUID workId,
      UUID chapterId,
      UUID sourceRevisionId,
      String sourceLanguage,
      String targetLanguage,
      byte[] sourceSha256,
      boolean sourceCurrent,
      boolean candidateReady) {
    private TranslationReviewSnapshotHasher.SnapshotContext hashContext() {
      return new TranslationReviewSnapshotHasher.SnapshotContext(
          candidateId,
          jobId,
          workId,
          chapterId,
          sourceRevisionId,
          sourceLanguage,
          targetLanguage,
          sourceSha256);
    }
  }

  public record SnapshotSegment(
      UUID sourceSegmentId,
      int ordinal,
      Integer editRevision,
      byte[] translatedSha256,
      boolean hasManualEdit) {
    public SnapshotSegment {
      translatedSha256 = translatedSha256 == null ? null : translatedSha256.clone();
    }

    @Override
    public byte[] translatedSha256() {
      return translatedSha256 == null ? null : translatedSha256.clone();
    }
  }

  public record Readiness(
      Outcome outcome,
      UUID jobId,
      UUID candidateId,
      UUID sourceRevisionId,
      String sourceLanguage,
      String targetLanguage,
      int editedSegments,
      int totalSegments,
      List<Integer> missingSegmentOrdinals,
      boolean pendingReview,
      byte[] snapshotDigest) {
    public Readiness {
      missingSegmentOrdinals = List.copyOf(missingSegmentOrdinals);
      snapshotDigest = snapshotDigest == null ? null : snapshotDigest.clone();
    }

    @Override
    public byte[] snapshotDigest() {
      return snapshotDigest == null ? null : snapshotDigest.clone();
    }

    public boolean readyForSubmission() {
      return outcome == Outcome.AVAILABLE
          && snapshotDigest != null
          && missingSegmentOrdinals.isEmpty()
          && !pendingReview;
    }

    private static Readiness available(
        Context context,
        int editedSegments,
        int totalSegments,
        List<Integer> missingSegmentOrdinals,
        boolean pendingReview,
        byte[] snapshotDigest) {
      return new Readiness(
          Outcome.AVAILABLE,
          context.jobId(),
          context.candidateId(),
          context.sourceRevisionId(),
          context.sourceLanguage(),
          context.targetLanguage(),
          editedSegments,
          totalSegments,
          missingSegmentOrdinals,
          pendingReview,
          snapshotDigest);
    }

    private static Readiness notFound() {
      return new Readiness(
          Outcome.NOT_FOUND, null, null, null, null, null, 0, 0, List.of(), false, null);
    }

    private static Readiness staleSource() {
      return new Readiness(
          Outcome.STALE_SOURCE, null, null, null, null, null, 0, 0, List.of(), false, null);
    }

    private static Readiness candidateNotReady() {
      return new Readiness(
          Outcome.CANDIDATE_NOT_READY, null, null, null, null, null, 0, 0, List.of(), false, null);
    }
  }

  public record SubmissionReceipt(
      UUID submissionId,
      UUID candidateId,
      String status,
      String snapshotDigest,
      int segmentCount,
      Instant submittedAt,
      boolean replayed) {}

  public record SubmissionResult(
      Outcome outcome,
      SubmissionReceipt receipt,
      int editedSegments,
      int totalSegments,
      String currentEtag) {
    private static SubmissionResult created(SubmissionReceipt receipt) {
      return new SubmissionResult(
          Outcome.CREATED, receipt, receipt.segmentCount(), receipt.segmentCount(), null);
    }

    private static SubmissionResult replayed(SubmissionReceipt receipt) {
      return new SubmissionResult(
          Outcome.REPLAYED, receipt, receipt.segmentCount(), receipt.segmentCount(), null);
    }

    private static SubmissionResult notFound() {
      return new SubmissionResult(Outcome.NOT_FOUND, null, 0, 0, null);
    }

    private static SubmissionResult staleSource() {
      return new SubmissionResult(Outcome.STALE_SOURCE, null, 0, 0, null);
    }

    private static SubmissionResult candidateNotReady() {
      return new SubmissionResult(Outcome.CANDIDATE_NOT_READY, null, 0, 0, null);
    }

    private static SubmissionResult incomplete(int edited, int total) {
      return new SubmissionResult(Outcome.INCOMPLETE, null, edited, total, null);
    }

    private static SubmissionResult staleEtag(String currentEtag) {
      return new SubmissionResult(Outcome.STALE_ETAG, null, 0, 0, currentEtag);
    }

    private static SubmissionResult pendingConflict() {
      return new SubmissionResult(Outcome.PENDING_CONFLICT, null, 0, 0, null);
    }
  }

  public enum Outcome {
    AVAILABLE,
    NOT_FOUND,
    STALE_SOURCE,
    CANDIDATE_NOT_READY,
    CREATED,
    REPLAYED,
    INCOMPLETE,
    STALE_ETAG,
    PENDING_CONFLICT
  }

  public static final class IdempotencyKeyReusedException extends RuntimeException {}

  public static final class SubmissionConflictException extends RuntimeException {}
}
