package com.novelweb.translation.jobs;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Persists authorized candidate corrections as immutable per-segment revisions. */
@Repository
@Profile("postgres")
public class TranslationCandidateEditRepository {

  private final DataSource dataSource;

  public TranslationCandidateEditRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public EditResult appendEdit(
      UUID actorUserId,
      UUID jobId,
      UUID sourceSegmentId,
      int expectedEditRevision,
      byte[] idempotencyKeySha256,
      byte[] requestSha256,
      byte[] translatedSha256,
      String translatedText) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        EditContext context =
            lockWritableCandidateSegment(connection, actorUserId, jobId, sourceSegmentId);
        EditResult replay =
            findReplay(
                connection,
                actorUserId,
                context.candidateId(),
                sourceSegmentId,
                idempotencyKeySha256,
                requestSha256,
                jobId);
        if (replay != null) {
          connection.commit();
          return replay;
        }
        if (!context.sourceRevisionCurrent()) throw new SourceRevisionStaleException();
        if (!context.candidateReady()) throw new CandidateNotReadyException();

        int currentEditRevision =
            currentEditRevision(connection, context.candidateId(), sourceSegmentId);
        if (expectedEditRevision != currentEditRevision) {
          throw new EditRevisionConflictException(currentEditRevision);
        }
        EditResult created =
            insertEdit(
                connection,
                actorUserId,
                jobId,
                context.candidateId(),
                sourceSegmentId,
                currentEditRevision + 1,
                idempotencyKeySha256,
                requestSha256,
                translatedSha256,
                translatedText);
        connection.commit();
        return created;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (CandidateNotFoundException
        | SourceRevisionStaleException
        | CandidateNotReadyException
        | EditRevisionConflictException
        | IdempotencyKeyReusedException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new EditStorageConflictException();
      throw new IllegalStateException("Unable to save the translation candidate edit", exception);
    }
  }

  private static EditContext lockWritableCandidateSegment(
      Connection connection, UUID actorUserId, UUID jobId, UUID sourceSegmentId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT candidate.id AS candidate_id,
                       (chapter.status = 'ready' AND chapter.source_revision_id = job.source_revision_id
                         AND revision.content_hash = job.source_sha256
                         AND candidate.source_sha256 = job.source_sha256) AS source_revision_current,
                       (job.provider_id = 'fake-local' AND job.status = 'review_required'
                         AND candidate.status = 'ready_for_review'
                         AND (SELECT count(*) FROM translation_candidate_segments candidate_segment
                              WHERE candidate_segment.candidate_id = candidate.id)
                           = (SELECT count(*) FROM translation_unit_segments unit_segment
                              WHERE unit_segment.job_id = job.id
                                AND unit_segment.source_revision_id = job.source_revision_id))
                         AS candidate_ready
                FROM translation_jobs job
                JOIN works work ON work.id = job.work_id
                JOIN libraries library ON library.id = work.library_id
                JOIN chapters chapter ON chapter.id = job.chapter_id AND chapter.work_id = job.work_id
                JOIN chapter_revisions revision
                  ON revision.id = job.source_revision_id AND revision.chapter_id = job.chapter_id
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                JOIN translation_candidates candidate
                  ON candidate.id = (SELECT latest.id FROM translation_candidates latest
                                     WHERE latest.job_id = job.id
                                     ORDER BY latest.candidate_no DESC LIMIT 1)
                JOIN translation_candidate_segments candidate_segment
                  ON candidate_segment.candidate_id = candidate.id
                 AND candidate_segment.source_segment_id = ?
                JOIN segments source_segment
                  ON source_segment.chapter_revision_id = candidate.source_revision_id
                 AND source_segment.id = candidate_segment.source_segment_id
                WHERE job.id = ? AND work.deleted_at IS NULL AND library.deleted_at IS NULL
                  AND can_access_library(library.id, actor.id, TRUE)
                FOR UPDATE OF actor, job, chapter, candidate, candidate_segment
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, sourceSegmentId);
      select.setObject(3, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new CandidateNotFoundException();
        return new EditContext(
            row.getObject("candidate_id", UUID.class),
            row.getBoolean("source_revision_current"),
            row.getBoolean("candidate_ready"));
      }
    }
  }

  private static EditResult findReplay(
      Connection connection,
      UUID actorUserId,
      UUID candidateId,
      UUID sourceSegmentId,
      byte[] idempotencyKeySha256,
      byte[] requestSha256,
      UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT edit_revision, translated_text, request_sha256, created_at
                FROM translation_candidate_segment_edits
                WHERE editor_user_id = ? AND candidate_id = ? AND source_segment_id = ?
                  AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, candidateId);
      select.setObject(3, sourceSegmentId);
      select.setBytes(4, idempotencyKeySha256);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return null;
        if (!MessageDigest.isEqual(requestSha256, row.getBytes("request_sha256"))) {
          throw new IdempotencyKeyReusedException();
        }
        return new EditResult(
            jobId,
            candidateId,
            sourceSegmentId,
            row.getInt("edit_revision"),
            row.getString("translated_text"),
            row.getTimestamp("created_at").toInstant(),
            true);
      }
    }
  }

  private static int currentEditRevision(
      Connection connection, UUID candidateId, UUID sourceSegmentId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT COALESCE(MAX(edit_revision), 0)::integer AS current_revision
                FROM translation_candidate_segment_edits
                WHERE candidate_id = ? AND source_segment_id = ?
                """)) {
      select.setObject(1, candidateId);
      select.setObject(2, sourceSegmentId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt("current_revision");
      }
    }
  }

  private static EditResult insertEdit(
      Connection connection,
      UUID actorUserId,
      UUID jobId,
      UUID candidateId,
      UUID sourceSegmentId,
      int editRevision,
      byte[] idempotencyKeySha256,
      byte[] requestSha256,
      byte[] translatedSha256,
      String translatedText)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_candidate_segment_edits
                    (candidate_id, source_segment_id, edit_revision, editor_user_id, translated_text,
                     translated_sha256, idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING created_at
                """)) {
      insert.setObject(1, candidateId);
      insert.setObject(2, sourceSegmentId);
      insert.setInt(3, editRevision);
      insert.setObject(4, actorUserId);
      insert.setString(5, translatedText);
      insert.setBytes(6, translatedSha256);
      insert.setBytes(7, idempotencyKeySha256);
      insert.setBytes(8, requestSha256);
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        return new EditResult(
            jobId,
            candidateId,
            sourceSegmentId,
            editRevision,
            translatedText,
            row.getTimestamp("created_at").toInstant(),
            false);
      }
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record EditContext(
      UUID candidateId, boolean sourceRevisionCurrent, boolean candidateReady) {}

  public record EditResult(
      UUID jobId,
      UUID candidateId,
      UUID sourceSegmentId,
      int editRevision,
      String translatedText,
      Instant createdAt,
      boolean replayed) {}

  public static final class CandidateNotFoundException extends RuntimeException {}

  public static final class SourceRevisionStaleException extends RuntimeException {}

  public static final class CandidateNotReadyException extends RuntimeException {}

  public static final class IdempotencyKeyReusedException extends RuntimeException {}

  public static final class EditStorageConflictException extends RuntimeException {}

  public static final class EditRevisionConflictException extends RuntimeException {
    private final int currentEditRevision;

    public EditRevisionConflictException(int currentEditRevision) {
      this.currentEditRevision = currentEditRevision;
    }

    public int currentEditRevision() {
      return currentEditRevision;
    }
  }
}
