package com.novelweb.translation.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Reads fake-local candidate text only for writers and only while its source snapshot is current.
 */
@Repository
@Profile("postgres")
public class TranslationCandidatePreviewRepository {

  private final DataSource dataSource;

  public TranslationCandidatePreviewRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Optional<TranslationCandidatePreviewService.CandidatePreview> findCurrentReadyForReview(
      UUID actorUserId, UUID jobId, int offset, int limit) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        Optional<CandidateContext> context = findWritableContext(connection, actorUserId, jobId);
        if (context.isEmpty()) {
          connection.commit();
          return Optional.empty();
        }
        CandidateContext candidate = context.orElseThrow();
        if (!candidate.sourceRevisionCurrent()) {
          throw new SourceRevisionStaleException();
        }
        if (!candidate.previewReady()) {
          throw new CandidateNotReadyException();
        }
        List<TranslationCandidatePreviewService.PreviewSegment> segments =
            findSegments(connection, candidate.candidateId(), offset, limit);
        connection.commit();
        return Optional.of(
            new TranslationCandidatePreviewService.CandidatePreview(
                jobId,
                candidate.candidateId(),
                candidate.sourceRevisionId(),
                true,
                candidate.sourceLanguage(),
                candidate.targetLanguage(),
                "fake-local",
                "ready_for_review",
                offset,
                limit,
                candidate.totalSegments(),
                segments));
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read translation candidate preview", exception);
    }
  }

  private static Optional<CandidateContext> findWritableContext(
      Connection connection, UUID actorUserId, UUID jobId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT job.id, job.source_revision_id, job.source_language, job.target_language,
                       candidate.id AS candidate_id,
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
                         AS preview_ready,
                       (SELECT count(*)::integer FROM translation_candidate_segments candidate_segment
                         WHERE candidate_segment.candidate_id = candidate.id) AS total_segments
                FROM translation_jobs job
                JOIN works work ON work.id = job.work_id
                JOIN libraries library ON library.id = work.library_id
                JOIN chapters chapter ON chapter.id = job.chapter_id AND chapter.work_id = job.work_id
                JOIN chapter_revisions revision
                  ON revision.id = job.source_revision_id AND revision.chapter_id = job.chapter_id
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                LEFT JOIN LATERAL (
                    SELECT id, status, source_sha256 FROM translation_candidates
                    WHERE job_id = job.id ORDER BY candidate_no DESC LIMIT 1
                ) candidate ON TRUE
                WHERE job.id = ? AND work.deleted_at IS NULL AND library.deleted_at IS NULL
                  AND can_access_library(library.id, actor.id, TRUE)
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        UUID candidateId = row.getObject("candidate_id", UUID.class);
        if (candidateId == null) return Optional.empty();
        return Optional.of(
            new CandidateContext(
                row.getObject("source_revision_id", UUID.class),
                candidateId,
                row.getString("source_language"),
                row.getString("target_language"),
                row.getBoolean("source_revision_current"),
                row.getBoolean("preview_ready"),
                row.getInt("total_segments")));
      }
    }
  }

  private static List<TranslationCandidatePreviewService.PreviewSegment> findSegments(
      Connection connection, UUID candidateId, int offset, int limit) throws SQLException {
    List<TranslationCandidatePreviewService.PreviewSegment> segments = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT source_segment.id, source_segment.ordinal, source_segment.segment_type,
                       source_segment.source_text, candidate_segment.translated_text
                FROM translation_candidate_segments candidate_segment
                JOIN translation_candidates candidate
                  ON candidate.id = candidate_segment.candidate_id
                JOIN segments source_segment
                  ON source_segment.chapter_revision_id = candidate.source_revision_id
                 AND source_segment.id = candidate_segment.source_segment_id
                WHERE candidate.id = ?
                ORDER BY source_segment.ordinal, source_segment.id
                OFFSET ? LIMIT ?
                """)) {
      select.setObject(1, candidateId);
      select.setInt(2, offset);
      select.setInt(3, limit);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          segments.add(
              new TranslationCandidatePreviewService.PreviewSegment(
                  rows.getObject("id", UUID.class),
                  rows.getInt("ordinal"),
                  rows.getString("segment_type"),
                  rows.getString("source_text"),
                  rows.getString("translated_text")));
        }
      }
    }
    return List.copyOf(segments);
  }

  private record CandidateContext(
      UUID sourceRevisionId,
      UUID candidateId,
      String sourceLanguage,
      String targetLanguage,
      boolean sourceRevisionCurrent,
      boolean previewReady,
      int totalSegments) {}

  public static final class SourceRevisionStaleException extends RuntimeException {}

  public static final class CandidateNotReadyException extends RuntimeException {}
}
