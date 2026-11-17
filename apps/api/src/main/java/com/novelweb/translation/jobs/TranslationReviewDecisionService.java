package com.novelweb.translation.jobs;

import com.novelweb.identity.auth.PolicyService;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Appends one reason-coded decision against an immutable manual-edit snapshot. */
@Service
@Profile("postgres")
public class TranslationReviewDecisionService {

  private static final byte[] REQUEST_DOMAIN =
      "novel-translation-review-decision-v1\0".getBytes(StandardCharsets.UTF_8);
  private static final byte[] IDEMPOTENCY_DOMAIN =
      "novel-translation-review-decision-key-v1\0".getBytes(StandardCharsets.UTF_8);

  private final DataSource dataSource;
  private final PolicyService policyService;

  public TranslationReviewDecisionService(DataSource dataSource, PolicyService policyService) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
  }

  public DecisionResult decide(
      UUID reviewerId,
      UUID submissionId,
      String expectedEtag,
      String idempotencyKey,
      DecisionCommand command) {
    Objects.requireNonNull(reviewerId, "reviewer id is required");
    Objects.requireNonNull(submissionId, "submission id is required");
    Objects.requireNonNull(expectedEtag, "decision ETag is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");
    Objects.requireNonNull(command, "decision is required");
    byte[] keyDigest = idempotencyDigest(reviewerId, submissionId, idempotencyKey);
    byte[] requestDigest = requestDigest(reviewerId, submissionId, expectedEtag, command);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<LockedSubmission> selected =
            lockEligibleSubmission(connection, reviewerId, submissionId);
        if (selected.isEmpty()) {
          connection.commit();
          return DecisionResult.notFound();
        }
        LockedSubmission submission = selected.orElseThrow();

        UUID assignmentId = lockActiveReviewerRole(connection, reviewerId).orElse(null);
        if (assignmentId == null) {
          connection.commit();
          return DecisionResult.notFound();
        }

        try (PreparedStatement policyLock =
            connection.prepareStatement("LOCK TABLE policy_documents IN SHARE MODE")) {
          policyLock.execute();
        }
        PolicyService.RequiredAcceptance acceptance = policyService.requiredAcceptance(reviewerId);
        if (acceptance == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return DecisionResult.policyRequired();
        }
        if (acceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return DecisionResult.policyNotConfigured();
        }

        Optional<DecisionReceipt> prior =
            findIdempotentDecision(connection, reviewerId, submissionId, keyDigest);
        if (prior.isPresent()) {
          DecisionReceipt receipt = prior.orElseThrow();
          if (!MessageDigest.isEqual(receipt.requestSha256(), requestDigest)) {
            connection.commit();
            return DecisionResult.keyReused();
          }
          connection.commit();
          return DecisionResult.replayed(receipt.publicReceipt());
        }

        String etag = TranslationReviewSnapshotHasher.etag(submission.snapshotSha256());
        if (!etag.equals(expectedEtag)) {
          connection.commit();
          return DecisionResult.staleEtag(etag);
        }
        if (!submission.status().equals("pending_review")) {
          connection.commit();
          return DecisionResult.invalidState(etag, submission.status());
        }
        if (!submission.sourceCurrent()) {
          connection.commit();
          return DecisionResult.staleSource(etag);
        }

        List<TranslationCandidateReviewRepository.SnapshotSegment> segments =
            readSnapshotSegments(connection, submission);
        if (segments.size() != submission.segmentCount()
            || !MessageDigest.isEqual(
                submission.snapshotSha256(),
                TranslationReviewSnapshotHasher.snapshotDigest(
                    submission.hashContext(), segments))) {
          connection.commit();
          return DecisionResult.storeUnavailable();
        }

        DecisionReceipt receipt =
            appendDecision(
                connection,
                reviewerId,
                assignmentId,
                submission,
                keyDigest,
                requestDigest,
                command);
        connection.commit();
        return DecisionResult.created(receipt.publicReceipt());
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record translation review decision", exception);
    }
  }

  private static Optional<LockedSubmission> lockEligibleSubmission(
      Connection connection, UUID reviewerId, UUID submissionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT submission.id, submission.candidate_id, submission.job_id,
                       submission.work_id, submission.chapter_id, submission.target_language,
                       submission.source_revision_id, submission.source_sha256,
                       submission.snapshot_sha256, submission.segment_count,
                       submission.submitted_by_user_id, submission.status,
                       work.owner_user_id, job.source_language,
                       (chapter.status = 'ready'
                         AND chapter.source_revision_id = submission.source_revision_id
                         AND source_revision.content_hash = submission.source_sha256) AS source_current
                  FROM translation_candidate_review_submissions submission
                  JOIN works work ON work.id = submission.work_id
                  JOIN chapters chapter
                    ON chapter.id = submission.chapter_id AND chapter.work_id = work.id
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
                 FOR UPDATE OF submission, work, chapter
                """)) {
      select.setObject(1, submissionId);
      select.setObject(2, reviewerId);
      select.setObject(3, reviewerId);
      select.setObject(4, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new LockedSubmission(
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
                row.getString("status"),
                row.getObject("owner_user_id", UUID.class),
                row.getString("source_language"),
                row.getBoolean("source_current")));
      }
    }
  }

  private static Optional<UUID> lockActiveReviewerRole(Connection connection, UUID reviewerId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT assignment.id
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
        return row.next() ? Optional.of(row.getObject("id", UUID.class)) : Optional.empty();
      }
    }
  }

  private static Optional<DecisionReceipt> findIdempotentDecision(
      Connection connection, UUID reviewerId, UUID submissionId, byte[] keyDigest)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, action_code, reason_code, snapshot_sha256, request_sha256, decided_at
                  FROM translation_candidate_review_decisions
                 WHERE reviewer_user_id = ? AND submission_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, reviewerId);
      select.setObject(2, submissionId);
      select.setBytes(3, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new DecisionReceipt(
                row.getObject("id", UUID.class),
                submissionId,
                row.getString("action_code"),
                row.getString("reason_code"),
                HexFormat.of().formatHex(row.getBytes("snapshot_sha256")),
                row.getBytes("request_sha256"),
                row.getObject("decided_at", OffsetDateTime.class).toInstant(),
                true));
      }
    }
  }

  private static List<TranslationCandidateReviewRepository.SnapshotSegment> readSnapshotSegments(
      Connection connection, LockedSubmission submission) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT snapshot_segment.source_segment_id, snapshot_segment.segment_ordinal,
                       snapshot_segment.edit_revision, snapshot_segment.translated_sha256,
                       edit.translated_text
                  FROM translation_candidate_review_submission_segments snapshot_segment
                  JOIN translation_unit_segments unit_segment
                    ON unit_segment.job_id = ?
                   AND unit_segment.source_revision_id = ?
                   AND unit_segment.source_segment_id = snapshot_segment.source_segment_id
                   AND unit_segment.segment_ordinal = snapshot_segment.segment_ordinal
                  JOIN translation_candidate_segment_edits edit
                    ON edit.candidate_id = snapshot_segment.candidate_id
                   AND edit.source_segment_id = snapshot_segment.source_segment_id
                   AND edit.edit_revision = snapshot_segment.edit_revision
                 WHERE snapshot_segment.submission_id = ?
                 ORDER BY snapshot_segment.segment_ordinal, snapshot_segment.source_segment_id
                """)) {
      select.setObject(1, submission.jobId());
      select.setObject(2, submission.sourceRevisionId());
      select.setObject(3, submission.submissionId());
      try (ResultSet rows = select.executeQuery()) {
        java.util.ArrayList<TranslationCandidateReviewRepository.SnapshotSegment> segments =
            new java.util.ArrayList<>();
        while (rows.next()) {
          byte[] translatedSha256 = rows.getBytes("translated_sha256");
          String translatedText = rows.getString("translated_text");
          if (!MessageDigest.isEqual(translatedSha256, sha256(translatedText))) {
            return List.of();
          }
          segments.add(
              new TranslationCandidateReviewRepository.SnapshotSegment(
                  rows.getObject("source_segment_id", UUID.class),
                  rows.getInt("segment_ordinal"),
                  rows.getInt("edit_revision"),
                  translatedSha256,
                  true));
        }
        return List.copyOf(segments);
      }
    }
  }

  private static DecisionReceipt appendDecision(
      Connection connection,
      UUID reviewerId,
      UUID assignmentId,
      LockedSubmission submission,
      byte[] keyDigest,
      byte[] requestDigest,
      DecisionCommand command)
      throws SQLException {
    UUID decisionId = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_candidate_review_decisions
                    (id, submission_id, reviewer_user_id, reviewer_assignment_id,
                     action_code, reason_code, snapshot_sha256,
                     idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING decided_at
                """)) {
      insert.setObject(1, decisionId);
      insert.setObject(2, submission.submissionId());
      insert.setObject(3, reviewerId);
      insert.setObject(4, assignmentId);
      insert.setString(5, command.actionCode());
      insert.setString(6, command.reasonCode());
      insert.setBytes(7, submission.snapshotSha256());
      insert.setBytes(8, keyDigest);
      insert.setBytes(9, requestDigest);
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        return new DecisionReceipt(
            decisionId,
            submission.submissionId(),
            command.actionCode(),
            command.reasonCode(),
            HexFormat.of().formatHex(submission.snapshotSha256()),
            requestDigest,
            row.getObject("decided_at", OffsetDateTime.class).toInstant(),
            false);
      }
    }
  }

  private static byte[] requestDigest(
      UUID reviewerId, UUID submissionId, String etag, DecisionCommand command) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.write(REQUEST_DOMAIN);
        writeUuid(output, reviewerId);
        writeUuid(output, submissionId);
        writeString(output, etag);
        writeString(output, command.actionCode());
        writeString(output, command.reasonCode() == null ? "" : command.reasonCode());
      }
      return sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode translation review decision", exception);
    }
  }

  private static byte[] idempotencyDigest(UUID reviewerId, UUID submissionId, String key) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.write(IDEMPOTENCY_DOMAIN);
        writeUuid(output, reviewerId);
        writeUuid(output, submissionId);
        writeString(output, key);
      }
      return sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to encode translation review idempotency key", exception);
    }
  }

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static byte[] sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
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

  public record DecisionCommand(String actionCode, String reasonCode) {}

  public record DecisionReceipt(
      UUID decisionId,
      UUID submissionId,
      String actionCode,
      String reasonCode,
      String snapshotDigest,
      byte[] requestSha256,
      Instant decidedAt,
      boolean replayed) {
    public DecisionReceipt {
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }

    private PublicDecisionReceipt publicReceipt() {
      return new PublicDecisionReceipt(
          decisionId, submissionId, actionCode, reasonCode, snapshotDigest, decidedAt, replayed);
    }
  }

  public record PublicDecisionReceipt(
      UUID decisionId,
      UUID submissionId,
      String actionCode,
      String reasonCode,
      String snapshotDigest,
      Instant decidedAt,
      boolean replayed) {}

  public record DecisionResult(
      DecisionOutcome outcome, PublicDecisionReceipt receipt, String currentEtag, String state) {
    private static DecisionResult created(PublicDecisionReceipt receipt) {
      return new DecisionResult(DecisionOutcome.CREATED, receipt, null, null);
    }

    private static DecisionResult replayed(PublicDecisionReceipt receipt) {
      return new DecisionResult(DecisionOutcome.REPLAYED, receipt, null, null);
    }

    private static DecisionResult notFound() {
      return new DecisionResult(DecisionOutcome.NOT_FOUND, null, null, null);
    }

    private static DecisionResult policyRequired() {
      return new DecisionResult(DecisionOutcome.POLICY_REQUIRED, null, null, null);
    }

    private static DecisionResult policyNotConfigured() {
      return new DecisionResult(DecisionOutcome.POLICY_NOT_CONFIGURED, null, null, null);
    }

    private static DecisionResult keyReused() {
      return new DecisionResult(DecisionOutcome.KEY_REUSED, null, null, null);
    }

    private static DecisionResult staleEtag(String etag) {
      return new DecisionResult(DecisionOutcome.STALE_ETAG, null, etag, null);
    }

    private static DecisionResult invalidState(String etag, String state) {
      return new DecisionResult(DecisionOutcome.INVALID_STATE, null, etag, state);
    }

    private static DecisionResult staleSource(String etag) {
      return new DecisionResult(DecisionOutcome.STALE_SOURCE, null, etag, null);
    }

    private static DecisionResult storeUnavailable() {
      return new DecisionResult(DecisionOutcome.STORE_UNAVAILABLE, null, null, null);
    }
  }

  public enum DecisionOutcome {
    CREATED,
    REPLAYED,
    NOT_FOUND,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    KEY_REUSED,
    STALE_ETAG,
    INVALID_STATE,
    STALE_SOURCE,
    STORE_UNAVAILABLE
  }

  private record LockedSubmission(
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
      String status,
      UUID ownerUserId,
      String sourceLanguage,
      boolean sourceCurrent) {
    private LockedSubmission {
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
