package com.novelweb.translation.jobs;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.library.LibraryCursorCodec;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Read-only assigned-reviewer queue and frozen manual snapshot detail. */
@Service
@Profile("postgres")
public class TranslationReviewQueryService {

  private static final int MAX_PAGE_SIZE = 50;

  private final DataSource dataSource;
  private final PolicyService policyService;
  private final LibraryCursorCodec cursorCodec;

  public TranslationReviewQueryService(
      DataSource dataSource, PolicyService policyService, LibraryCursorCodec cursorCodec) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursor codec is required");
  }

  public QueueResult queue(UUID reviewerId, String rawCursor, int limit) {
    Objects.requireNonNull(reviewerId, "reviewer id is required");
    if (limit < 1 || limit > MAX_PAGE_SIZE) return QueueResult.invalidLimit();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try {
        Gate gate = gate(connection, reviewerId);
        if (gate != Gate.AVAILABLE) {
          connection.commit();
          return QueueResult.gated(gate);
        }

        LibraryCursorCodec.CursorContext context =
            LibraryCursorCodec.translationReviewQueueContext(reviewerId);
        LibraryCursorCodec.Position position;
        try {
          cursorCodec.requireConfigured();
          position = rawCursor == null ? null : cursorCodec.decode(rawCursor, context);
          if (position != null && position.rank() != 0) {
            connection.commit();
            return QueueResult.invalidCursor();
          }
        } catch (LibraryCursorCodec.InvalidCursorException exception) {
          connection.commit();
          return QueueResult.invalidCursor();
        } catch (LibraryCursorCodec.SigningUnavailableException exception) {
          connection.commit();
          return QueueResult.cursorUnavailable();
        }

        List<QueueItem> selected = readQueue(connection, reviewerId, position, limit + 1);
        boolean hasMore = selected.size() > limit;
        List<QueueItem> items = List.copyOf(selected.subList(0, Math.min(limit, selected.size())));
        String nextCursor =
            hasMore
                ? cursorCodec.encode(
                    context,
                    new LibraryCursorCodec.Position(
                        0, items.getLast().submittedAt(), items.getLast().submissionId()))
                : null;
        connection.commit();
        return QueueResult.available(new QueuePage(items, nextCursor, hasMore));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load translation review queue", exception);
    }
  }

  public DetailResult detail(UUID reviewerId, UUID submissionId) {
    Objects.requireNonNull(reviewerId, "reviewer id is required");
    Objects.requireNonNull(submissionId, "submission id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try {
        Gate gate = gate(connection, reviewerId);
        if (gate != Gate.AVAILABLE) {
          connection.commit();
          return DetailResult.gated(gate);
        }
        Optional<SubmissionHeader> selected = findSubmission(connection, reviewerId, submissionId);
        if (selected.isEmpty()) {
          connection.commit();
          return DetailResult.notFound();
        }
        SubmissionHeader header = selected.orElseThrow();
        if (!header.sourceCurrent()) {
          connection.commit();
          return DetailResult.staleSource();
        }
        List<ReviewSegment> segments = readSnapshotSegments(connection, header);
        if (segments.size() != header.segmentCount()
            || !MessageDigest.isEqual(
                header.snapshotSha256(),
                TranslationReviewSnapshotHasher.snapshotDigest(
                    header.hashContext(),
                    segments.stream()
                        .map(
                            segment ->
                                new TranslationCandidateReviewRepository.SnapshotSegment(
                                    segment.sourceSegmentId(),
                                    segment.ordinal(),
                                    segment.editRevision(),
                                    HexFormat.of().parseHex(segment.translatedSha256()),
                                    true))
                        .toList()))) {
          connection.commit();
          return DetailResult.unavailable();
        }
        TranslationReviewDetail detail =
            new TranslationReviewDetail(
                header.submissionId(),
                header.status(),
                header.workTitle(),
                header.chapterTitle(),
                header.submittedByUserId(),
                header.submittedByDisplayName(),
                header.sourceRevisionId(),
                header.sourceLanguage(),
                header.targetLanguage(),
                HexFormat.of().formatHex(header.snapshotSha256()),
                header.segmentCount(),
                header.submittedAt(),
                header.status().equals("pending_review"),
                segments);
        String etag = TranslationReviewSnapshotHasher.etag(header.snapshotSha256());
        connection.commit();
        return DetailResult.available(detail, etag);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load translation review detail", exception);
    }
  }

  private Gate gate(Connection connection, UUID reviewerId) throws SQLException {
    if (!hasActiveReviewerRole(connection, reviewerId)) return Gate.REVIEWER_REQUIRED;
    PolicyService.RequiredAcceptance acceptance = policyService.requiredAcceptance(reviewerId);
    return switch (acceptance) {
      case COMPLETE -> Gate.AVAILABLE;
      case REQUIRED -> Gate.POLICY_REQUIRED;
      case NOT_CONFIGURED -> Gate.POLICY_NOT_CONFIGURED;
    };
  }

  static boolean hasActiveReviewerRole(Connection connection, UUID reviewerId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1
                  FROM security_role_assignments assignment
                  JOIN users reviewer
                    ON reviewer.id = assignment.user_id AND reviewer.status = 'active'
                 WHERE assignment.user_id = ?
                   AND assignment.role_code = 'publication_reviewer'
                   AND assignment.assigned_at <= clock_timestamp()
                   AND assignment.revoked_at IS NULL
                 ORDER BY assignment.assigned_at DESC, assignment.id DESC
                 LIMIT 1
                 FOR SHARE OF assignment, reviewer
                """)) {
      select.setObject(1, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static List<QueueItem> readQueue(
      Connection connection, UUID reviewerId, LibraryCursorCodec.Position position, int fetchLimit)
      throws SQLException {
    String cursorPredicate =
        position == null
            ? ""
            : " AND (submission.submitted_at < ? OR"
                + " (submission.submitted_at = ? AND submission.id < ?))";
    String sql =
        """
            SELECT submission.id, submission.work_id, submission.chapter_id,
                   submission.submitted_at, submission.target_language,
                   submission.source_revision_id, submission.segment_count,
                   submission.submitted_by_user_id, submitter.display_name AS submitter_name,
                   work.title AS work_title, chapter.title AS chapter_title,
                   job.source_language,
                   (chapter.status = 'ready'
                     AND chapter.source_revision_id = submission.source_revision_id
                     AND source_revision.content_hash = submission.source_sha256) AS source_current
              FROM translation_candidate_review_submissions submission
              JOIN works work ON work.id = submission.work_id
              JOIN chapters chapter ON chapter.id = submission.chapter_id
              JOIN chapter_revisions source_revision
                ON source_revision.id = submission.source_revision_id
               AND source_revision.chapter_id = submission.chapter_id
              JOIN translation_jobs job ON job.id = submission.job_id
              JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
              JOIN users submitter
                ON submitter.id = submission.submitted_by_user_id AND submitter.status = 'active'
             WHERE submission.status = 'pending_review'
               AND work.status = 'active'
               AND work.visibility = 'private' AND work.deleted_at IS NULL
               AND chapter.work_id = work.id AND work.owner_user_id <> ?
               AND submission.submitted_by_user_id <> ?
               AND NOT EXISTS (
                   SELECT 1
                     FROM translation_candidate_review_submission_segments snapshot_segment
                     JOIN translation_candidate_segment_edits edit
                       ON edit.candidate_id = snapshot_segment.candidate_id
                      AND edit.source_segment_id = snapshot_segment.source_segment_id
                      AND edit.edit_revision = snapshot_segment.edit_revision
                    WHERE snapshot_segment.submission_id = submission.id
                      AND edit.editor_user_id = ?)
            """
            + cursorPredicate
            + " ORDER BY submission.submitted_at DESC, submission.id DESC LIMIT ?";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      int index = 1;
      select.setObject(index++, reviewerId);
      select.setObject(index++, reviewerId);
      select.setObject(index++, reviewerId);
      if (position != null) {
        OffsetDateTime submittedAt = position.createdAt().atOffset(ZoneOffset.UTC);
        select.setObject(index++, submittedAt);
        select.setObject(index++, submittedAt);
        select.setObject(index++, position.itemId());
      }
      select.setInt(index, fetchLimit);
      try (ResultSet rows = select.executeQuery()) {
        List<QueueItem> items = new ArrayList<>();
        while (rows.next()) {
          items.add(
              new QueueItem(
                  rows.getObject("id", UUID.class),
                  rows.getObject("work_id", UUID.class),
                  rows.getObject("chapter_id", UUID.class),
                  rows.getString("work_title"),
                  rows.getString("chapter_title"),
                  rows.getString("source_language"),
                  rows.getString("target_language"),
                  rows.getInt("segment_count"),
                  rows.getObject("submitted_by_user_id", UUID.class),
                  rows.getString("submitter_name"),
                  rows.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                  rows.getBoolean("source_current")));
        }
        return List.copyOf(items);
      }
    }
  }

  private static Optional<SubmissionHeader> findSubmission(
      Connection connection, UUID reviewerId, UUID submissionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT submission.id, submission.candidate_id, submission.job_id,
                       submission.work_id, submission.chapter_id, submission.target_language,
                       submission.source_revision_id, submission.source_sha256,
                       submission.snapshot_sha256, submission.segment_count,
                       submission.submitted_by_user_id, submitter.display_name AS submitter_name,
                       submission.submitted_at, submission.status,
                       work.owner_user_id, work.title AS work_title, chapter.title AS chapter_title,
                       job.source_language,
                       (chapter.status = 'ready'
                         AND chapter.source_revision_id = submission.source_revision_id
                         AND source_revision.content_hash = submission.source_sha256) AS source_current
                  FROM translation_candidate_review_submissions submission
                  JOIN works work ON work.id = submission.work_id
                  JOIN chapters chapter ON chapter.id = submission.chapter_id
                  JOIN chapter_revisions source_revision
                    ON source_revision.id = submission.source_revision_id
                   AND source_revision.chapter_id = submission.chapter_id
                  JOIN translation_jobs job ON job.id = submission.job_id
                  JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
                  JOIN users submitter
                    ON submitter.id = submission.submitted_by_user_id AND submitter.status = 'active'
                 WHERE submission.id = ?
                   AND work.status = 'active'
                   AND work.visibility = 'private' AND work.deleted_at IS NULL
                   AND chapter.work_id = work.id
                   AND work.owner_user_id <> ?
                   AND submission.submitted_by_user_id <> ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM translation_candidate_review_submission_segments snapshot_segment
                         JOIN translation_candidate_segment_edits edit
                           ON edit.candidate_id = snapshot_segment.candidate_id
                          AND edit.source_segment_id = snapshot_segment.source_segment_id
                          AND edit.edit_revision = snapshot_segment.edit_revision
                        WHERE snapshot_segment.submission_id = submission.id
                          AND edit.editor_user_id = ?)
                """)) {
      select.setObject(1, submissionId);
      select.setObject(2, reviewerId);
      select.setObject(3, reviewerId);
      select.setObject(4, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new SubmissionHeader(
                row.getObject("id", UUID.class),
                row.getObject("candidate_id", UUID.class),
                row.getObject("job_id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getObject("chapter_id", UUID.class),
                row.getString("target_language"),
                row.getObject("source_revision_id", UUID.class),
                row.getBytes("source_sha256"),
                row.getBytes("snapshot_sha256"),
                row.getInt("segment_count"),
                row.getObject("submitted_by_user_id", UUID.class),
                row.getString("submitter_name"),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                row.getString("status"),
                row.getObject("owner_user_id", UUID.class),
                row.getString("work_title"),
                row.getString("chapter_title"),
                row.getString("source_language"),
                row.getBoolean("source_current")));
      }
    }
  }

  private static List<ReviewSegment> readSnapshotSegments(
      Connection connection, SubmissionHeader header) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT snapshot_segment.source_segment_id, snapshot_segment.segment_ordinal,
                       snapshot_segment.edit_revision, snapshot_segment.translated_sha256,
                       source_segment.source_text, edit.translated_text
                  FROM translation_candidate_review_submission_segments snapshot_segment
                  JOIN translation_unit_segments unit_segment
                    ON unit_segment.job_id = ?
                   AND unit_segment.source_revision_id = ?
                   AND unit_segment.source_segment_id = snapshot_segment.source_segment_id
                   AND unit_segment.segment_ordinal = snapshot_segment.segment_ordinal
                  JOIN segments source_segment
                    ON source_segment.id = snapshot_segment.source_segment_id
                   AND source_segment.chapter_revision_id = ?
                  JOIN translation_candidate_segment_edits edit
                    ON edit.candidate_id = snapshot_segment.candidate_id
                   AND edit.source_segment_id = snapshot_segment.source_segment_id
                   AND edit.edit_revision = snapshot_segment.edit_revision
                 WHERE snapshot_segment.submission_id = ?
                 ORDER BY snapshot_segment.segment_ordinal, snapshot_segment.source_segment_id
                """)) {
      select.setObject(1, header.jobId());
      select.setObject(2, header.sourceRevisionId());
      select.setObject(3, header.sourceRevisionId());
      select.setObject(4, header.submissionId());
      try (ResultSet rows = select.executeQuery()) {
        List<ReviewSegment> segments = new ArrayList<>();
        while (rows.next()) {
          UUID sourceSegmentId = rows.getObject("source_segment_id", UUID.class);
          int revision = rows.getInt("edit_revision");
          String translatedText = rows.getString("translated_text");
          byte[] translatedSha256 = rows.getBytes("translated_sha256");
          if (!MessageDigest.isEqual(translatedSha256, sha256(translatedText))) {
            throw new IllegalStateException("Submitted manual translation digest is inconsistent");
          }
          segments.add(
              new ReviewSegment(
                  sourceSegmentId,
                  rows.getInt("segment_ordinal"),
                  revision,
                  HexFormat.of().formatHex(translatedSha256),
                  rows.getString("source_text"),
                  translatedText));
        }
        return List.copyOf(segments);
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

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public enum Gate {
    AVAILABLE,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED
  }

  public record QueueItem(
      UUID submissionId,
      @com.fasterxml.jackson.annotation.JsonIgnore UUID workId,
      @com.fasterxml.jackson.annotation.JsonIgnore UUID chapterId,
      String workTitle,
      String chapterTitle,
      String sourceLanguage,
      String targetLanguage,
      int segmentCount,
      @com.fasterxml.jackson.annotation.JsonIgnore UUID submittedByUserId,
      String submitterDisplayName,
      Instant submittedAt,
      boolean sourceCurrent) {}

  public record QueuePage(List<QueueItem> items, String nextCursor, boolean hasMore) {
    public QueuePage {
      items = List.copyOf(items);
    }
  }

  public record ReviewSegment(
      @com.fasterxml.jackson.annotation.JsonIgnore UUID sourceSegmentId,
      int ordinal,
      int editRevision,
      String translatedSha256,
      String sourceText,
      String submittedText) {}

  public record TranslationReviewDetail(
      UUID submissionId,
      String status,
      String workTitle,
      String chapterTitle,
      @com.fasterxml.jackson.annotation.JsonIgnore UUID submittedByUserId,
      String submitterDisplayName,
      @com.fasterxml.jackson.annotation.JsonIgnore UUID sourceRevisionId,
      String sourceLanguage,
      String targetLanguage,
      String snapshotDigest,
      int segmentCount,
      Instant submittedAt,
      boolean canDecide,
      List<ReviewSegment> segments) {
    public TranslationReviewDetail {
      segments = List.copyOf(segments);
    }
  }

  public record QueueResult(QueueOutcome outcome, QueuePage page) {
    private static QueueResult available(QueuePage page) {
      return new QueueResult(QueueOutcome.AVAILABLE, page);
    }

    private static QueueResult gated(Gate gate) {
      return new QueueResult(QueueOutcome.valueOf(gate.name()), null);
    }

    private static QueueResult invalidLimit() {
      return new QueueResult(QueueOutcome.INVALID_LIMIT, null);
    }

    private static QueueResult invalidCursor() {
      return new QueueResult(QueueOutcome.INVALID_CURSOR, null);
    }

    private static QueueResult cursorUnavailable() {
      return new QueueResult(QueueOutcome.CURSOR_UNAVAILABLE, null);
    }
  }

  public enum QueueOutcome {
    AVAILABLE,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    INVALID_LIMIT,
    INVALID_CURSOR,
    CURSOR_UNAVAILABLE
  }

  public record DetailResult(DetailOutcome outcome, TranslationReviewDetail detail, String etag) {
    private static DetailResult available(TranslationReviewDetail detail, String etag) {
      return new DetailResult(DetailOutcome.AVAILABLE, detail, etag);
    }

    private static DetailResult gated(Gate gate) {
      return new DetailResult(DetailOutcome.valueOf(gate.name()), null, null);
    }

    private static DetailResult notFound() {
      return new DetailResult(DetailOutcome.NOT_FOUND, null, null);
    }

    private static DetailResult staleSource() {
      return new DetailResult(DetailOutcome.STALE_SOURCE, null, null);
    }

    private static DetailResult unavailable() {
      return new DetailResult(DetailOutcome.UNAVAILABLE, null, null);
    }
  }

  public enum DetailOutcome {
    AVAILABLE,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    NOT_FOUND,
    STALE_SOURCE,
    UNAVAILABLE
  }

  private record SubmissionHeader(
      UUID submissionId,
      UUID candidateId,
      UUID jobId,
      UUID workId,
      UUID chapterId,
      String targetLanguage,
      UUID sourceRevisionId,
      byte[] sourceSha256,
      byte[] snapshotSha256,
      int segmentCount,
      UUID submittedByUserId,
      String submittedByDisplayName,
      Instant submittedAt,
      String status,
      UUID ownerUserId,
      String workTitle,
      String chapterTitle,
      String sourceLanguage,
      boolean sourceCurrent) {
    private SubmissionHeader {
      sourceSha256 = sourceSha256.clone();
      snapshotSha256 = snapshotSha256.clone();
    }

    @Override
    public byte[] sourceSha256() {
      return sourceSha256.clone();
    }

    @Override
    public byte[] snapshotSha256() {
      return snapshotSha256.clone();
    }

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
}
