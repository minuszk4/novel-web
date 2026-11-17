package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationCostEstimate;
import com.novelweb.translation.provider.TranslationProviderExecutionGate.TranslationExecutionResult;
import com.novelweb.translation.provider.TranslationResponse;
import com.novelweb.translation.provider.TranslationResponse.SegmentResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Persists complete translated output only while its exact unit lease is current. */
@Repository
@Profile("postgres")
public class TranslationAttemptCompletionRepository {

  private static final int MAX_TRANSLATED_SEGMENT_BYTES = 1_048_576;

  private final DataSource dataSource;

  public TranslationAttemptCompletionRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public CompletionResult complete(
      TranslationUnitLeaseRepository.Lease lease, TranslationResponse response) {
    return complete(lease, response, null);
  }

  public CompletionResult completeExecution(
      TranslationUnitLeaseRepository.Lease lease, TranslationExecutionResult execution) {
    Objects.requireNonNull(execution, "provider execution result is required");
    return complete(lease, execution.response(), execution.estimate());
  }

  private CompletionResult complete(
      TranslationUnitLeaseRepository.Lease lease,
      TranslationResponse response,
      TranslationCostEstimate estimate) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        UnitContext unit = lockUnitContext(connection, lease);
        List<SourceSegment> sourceSegments = loadUnitSegments(connection, lease.unitId());
        List<TranslatedSegment> translations = validateAndOrder(response, sourceSegments);
        byte[] responseSha256 = responseSha256(translations);
        AttemptContext attempt = lockAttempt(connection, lease.unitId(), response.attemptId());
        int attemptNo = attempt.attemptNo();

        if ("succeeded".equals(unit.status()) && "succeeded".equals(attempt.outcome())) {
          if (attempt.responseSha256() == null
              || !MessageDigest.isEqual(attempt.responseSha256(), responseSha256)) {
            throw new CompletionConflictException();
          }
          Candidate candidate =
              findCompletedCandidate(connection, unit, sourceSegments, translations);
          connection.commit();
          return new CompletionResult(
              attempt.id(),
              unit.jobId(),
              lease.unitId(),
              candidate.id(),
              translations.size(),
              candidate.status(),
              candidate.jobStatus(),
              true);
        }

        requireCurrentLiveLease(unit, lease, attempt);
        lockTranslatableJob(connection, lease.jobId());
        requireSourceRevisionIsCurrent(connection, unit);

        Candidate candidate = lockOrCreateCandidate(connection, unit);
        insertCandidateSegments(connection, candidate.id(), unit, translations);
        finishAttempt(connection, attempt.id(), response, estimate, responseSha256);
        insertConfirmedUsage(connection, attempt, response);
        markUnitSucceeded(connection, lease, attemptNo);
        JobProgress progress = refreshJobProgress(connection, unit.jobId());
        String candidateStatus =
            "review_required".equals(progress.jobStatus()) ? "ready_for_review" : "partial";
        updateCandidateStatus(connection, candidate.id(), candidateStatus);
        updateJobStatus(connection, unit.jobId(), progress);
        insertUnitCompletedEvent(connection, unit.jobId(), lease.unitId(), attempt.id(), attemptNo);
        if ("ready_for_review".equals(candidateStatus)) {
          insertCandidateReadyEvent(connection, unit.jobId(), candidate.id());
        } else if ("failed".equals(progress.jobStatus())) {
          insertJobFailedEvent(connection, unit.jobId(), lease.unitId(), attempt.id(), attemptNo);
        }
        connection.commit();
        return new CompletionResult(
            attempt.id(),
            unit.jobId(),
            lease.unitId(),
            candidate.id(),
            translations.size(),
            candidateStatus,
            progress.jobStatus(),
            false);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to persist translation attempt output", exception);
    }
  }

  private static UnitContext lockUnitContext(
      Connection connection, TranslationUnitLeaseRepository.Lease lease) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT unit.id, unit.job_id, unit.source_revision_id, unit.status,
                       unit.lease_owner, unit.lease_token, unit.lease_until,
                       unit.attempt_count, job.work_id, job.chapter_id,
                       job.provider_id, job.source_sha256
                FROM translation_units unit
                JOIN translation_jobs job ON job.id = unit.job_id
                WHERE unit.id = ? AND unit.job_id = ?
                FOR UPDATE OF unit
                """)) {
      select.setObject(1, lease.unitId());
      select.setObject(2, lease.jobId());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new StaleLeaseException();
        OffsetDateTime leaseUntil = row.getObject("lease_until", OffsetDateTime.class);
        return new UnitContext(
            row.getObject("id", UUID.class),
            row.getObject("job_id", UUID.class),
            row.getObject("source_revision_id", UUID.class),
            row.getString("status"),
            row.getString("lease_owner"),
            row.getObject("lease_token", UUID.class),
            leaseUntil == null ? null : leaseUntil.toInstant(),
            row.getInt("attempt_count"),
            row.getObject("work_id", UUID.class),
            row.getObject("chapter_id", UUID.class),
            row.getString("provider_id"),
            row.getBytes("source_sha256"));
      }
    }
  }

  private static List<SourceSegment> loadUnitSegments(Connection connection, UUID unitId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT segment_ordinal, source_segment_id
                FROM translation_unit_segments
                WHERE unit_id = ? ORDER BY segment_ordinal
                """)) {
      select.setObject(1, unitId);
      try (ResultSet rows = select.executeQuery()) {
        List<SourceSegment> segments = new ArrayList<>();
        int expectedOrdinal = 1;
        while (rows.next()) {
          int ordinal = rows.getInt("segment_ordinal");
          if (ordinal != expectedOrdinal++) throw new InvalidCompletionException();
          segments.add(new SourceSegment(rows.getObject("source_segment_id", UUID.class), ordinal));
        }
        if (segments.isEmpty()) throw new InvalidCompletionException();
        return List.copyOf(segments);
      }
    }
  }

  private static List<TranslatedSegment> validateAndOrder(
      TranslationResponse response, List<SourceSegment> sourceSegments) {
    Map<UUID, String> byId = new HashMap<>();
    for (SegmentResult segment : response.segments()) {
      if (byId.put(segment.id(), segment.translatedText()) != null) {
        throw new InvalidCompletionException();
      }
      if (segment.translatedText().getBytes(StandardCharsets.UTF_8).length
          > MAX_TRANSLATED_SEGMENT_BYTES) {
        throw new InvalidCompletionException();
      }
    }
    if (byId.size() != sourceSegments.size()) throw new InvalidCompletionException();
    List<TranslatedSegment> ordered = new ArrayList<>(sourceSegments.size());
    for (SourceSegment source : sourceSegments) {
      String translatedText = byId.remove(source.id());
      if (translatedText == null || translatedText.isBlank()) {
        throw new InvalidCompletionException();
      }
      ordered.add(
          new TranslatedSegment(
              source.id(), source.ordinal(), translatedText, sha256(translatedText)));
    }
    if (!byId.isEmpty()) throw new InvalidCompletionException();
    return List.copyOf(ordered);
  }

  private static AttemptContext lockAttempt(Connection connection, UUID unitId, UUID attemptId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, attempt_no, provider_id, outcome, request_sha256, response_sha256
                FROM translation_attempts WHERE id = ? AND unit_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, attemptId);
      select.setObject(2, unitId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new AttemptNotFoundException();
        return new AttemptContext(
            row.getObject("id", UUID.class),
            row.getInt("attempt_no"),
            row.getString("provider_id"),
            row.getString("outcome"),
            row.getBytes("request_sha256"),
            row.getBytes("response_sha256"));
      }
    }
  }

  private static void requireCurrentLiveLease(
      UnitContext unit, TranslationUnitLeaseRepository.Lease lease, AttemptContext attempt) {
    if (!"running".equals(unit.status())
        || !lease.workerId().equals(unit.leaseOwner())
        || !lease.leaseToken().equals(unit.leaseToken())
        || !lease.sourceRevisionId().equals(unit.sourceRevisionId())
        || unit.leaseUntil() == null
        || unit.attemptCount() != attempt.attemptNo()
        || (attempt.attemptNo() != lease.attemptCount() + 1
            && !("fake-local".equals(attempt.providerId())
                && attempt.attemptNo() == lease.attemptCount()))
        || !"running".equals(attempt.outcome())
        || !unit.providerId().equals(attempt.providerId())
        || attempt.requestSha256() == null
        || attempt.requestSha256().length != 32) {
      throw new StaleLeaseException();
    }
  }

  private static void lockTranslatableJob(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT status FROM translation_jobs WHERE id = ? FOR UPDATE")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next() || !"translating".equals(row.getString("status"))) {
          throw new CompletionNotAllowedException();
        }
      }
    }
  }

  private static void requireSourceRevisionIsCurrent(Connection connection, UnitContext unit)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT chapter.status, chapter.source_revision_id, revision.content_hash
                FROM chapters chapter
                JOIN chapter_revisions revision
                  ON revision.chapter_id = chapter.id AND revision.id = chapter.source_revision_id
                WHERE chapter.id = ? AND chapter.work_id = ?
                FOR SHARE OF chapter
                """)) {
      select.setObject(1, unit.chapterId());
      select.setObject(2, unit.workId());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()
            || !"ready".equals(row.getString("status"))
            || !unit.sourceRevisionId().equals(row.getObject("source_revision_id", UUID.class))
            || !MessageDigest.isEqual(unit.sourceSha256(), row.getBytes("content_hash"))) {
          throw new StaleSourceException();
        }
      }
    }
  }

  private static Candidate lockOrCreateCandidate(Connection connection, UnitContext unit)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, status FROM translation_candidates
                WHERE job_id = ? AND status IN ('assembling', 'partial')
                ORDER BY candidate_no DESC LIMIT 1 FOR UPDATE
                """)) {
      select.setObject(1, unit.jobId());
      try (ResultSet row = select.executeQuery()) {
        if (row.next()) {
          return new Candidate(
              row.getObject("id", UUID.class), row.getString("status"), "translating");
        }
      }
    }

    UUID candidateId = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_candidates
                    (id, job_id, work_id, chapter_id, target_language, source_revision_id,
                     candidate_no, source_sha256, status, created_by_user_id)
                SELECT ?, job.id, job.work_id, job.chapter_id, job.target_language,
                       job.source_revision_id,
                       COALESCE((SELECT max(candidate_no) + 1 FROM translation_candidates
                                 WHERE job_id = job.id), 1),
                       job.source_sha256, 'assembling', job.requested_by_user_id
                FROM translation_jobs job WHERE job.id = ?
                """)) {
      insert.setObject(1, candidateId);
      insert.setObject(2, unit.jobId());
      if (insert.executeUpdate() != 1) throw new CompletionNotAllowedException();
    }
    return new Candidate(candidateId, "assembling", "translating");
  }

  private static void insertCandidateSegments(
      Connection connection, UUID candidateId, UnitContext unit, List<TranslatedSegment> segments)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_candidate_segments
                    (candidate_id, job_id, source_revision_id, source_segment_id,
                     translated_text, translated_sha256)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      for (TranslatedSegment segment : segments) {
        insert.setObject(1, candidateId);
        insert.setObject(2, unit.jobId());
        insert.setObject(3, unit.sourceRevisionId());
        insert.setObject(4, segment.sourceSegmentId());
        insert.setString(5, segment.translatedText());
        insert.setBytes(6, segment.translatedSha256());
        insert.addBatch();
      }
      int[] inserted = insert.executeBatch();
      if (inserted.length != segments.size()) throw new CompletionConflictException();
      for (int result : inserted) {
        if (result != 1 && result != PreparedStatement.SUCCESS_NO_INFO) {
          throw new CompletionConflictException();
        }
      }
    }
  }

  private static void finishAttempt(
      Connection connection,
      UUID attemptId,
      TranslationResponse response,
      TranslationCostEstimate estimate,
      byte[] responseSha256)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_attempts
                SET provider_request_id = ?, provider_model = ?, outcome = 'succeeded',
                    response_sha256 = ?, estimate_amount = ?, estimate_currency = ?,
                    estimated_units = ?, estimated_billing_unit = ?, pricing_version = ?,
                    billed_units = ?, billed_billing_unit = ?, completed_at = clock_timestamp()
                WHERE id = ? AND outcome = 'running' AND completed_at IS NULL
                """)) {
      update.setString(1, response.providerRequestId());
      update.setString(2, response.providerModel());
      update.setBytes(3, responseSha256);
      if (estimate == null) {
        update.setNull(4, java.sql.Types.NUMERIC);
        update.setNull(5, java.sql.Types.VARCHAR);
        update.setNull(6, java.sql.Types.BIGINT);
        update.setNull(7, java.sql.Types.VARCHAR);
        update.setNull(8, java.sql.Types.VARCHAR);
      } else {
        update.setBigDecimal(4, estimate.amount());
        update.setString(5, estimate.currency());
        update.setLong(6, estimate.billableUnits());
        update.setString(7, estimate.billingUnit().name());
        update.setString(8, estimate.pricingVersion());
      }
      update.setLong(9, response.billedUnits());
      update.setString(10, response.billingUnit().name());
      update.setObject(11, attemptId);
      if (update.executeUpdate() != 1) throw new CompletionConflictException();
    }
  }

  private static void insertConfirmedUsage(
      Connection connection, AttemptContext attempt, TranslationResponse response)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_usage_ledger
                    (attempt_id, provider_id, provider_model, provider_request_id,
                     confirmed_units, billing_unit)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, attempt.id());
      insert.setString(2, attempt.providerId());
      insert.setString(3, response.providerModel());
      insert.setString(4, response.providerRequestId());
      insert.setLong(5, response.billedUnits());
      insert.setString(6, response.billingUnit().name());
      if (insert.executeUpdate() != 1) throw new CompletionConflictException();
    }
  }

  private static void markUnitSucceeded(
      Connection connection, TranslationUnitLeaseRepository.Lease lease, int attemptNo)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_units
                SET status = 'succeeded', lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, heartbeat_at = NULL, updated_at = clock_timestamp()
                WHERE id = ? AND job_id = ? AND source_revision_id = ? AND status = 'running'
                  AND lease_owner = ? AND lease_token = ? AND lease_until > clock_timestamp()
                  AND attempt_count = ?
                """)) {
      update.setObject(1, lease.unitId());
      update.setObject(2, lease.jobId());
      update.setObject(3, lease.sourceRevisionId());
      update.setString(4, lease.workerId());
      update.setObject(5, lease.leaseToken());
      update.setInt(6, attemptNo);
      if (update.executeUpdate() != 1) throw new StaleLeaseException();
    }
  }

  private static JobProgress refreshJobProgress(Connection connection, UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT count(*)::integer AS total_units,
                       count(*) FILTER (WHERE status = 'succeeded')::integer AS completed_units,
                       count(*) FILTER (WHERE status = 'failed')::integer AS failed_units
                FROM translation_units WHERE job_id = ?
                """)) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next() || row.getInt("total_units") < 1) throw new CompletionConflictException();
        int totalUnits = row.getInt("total_units");
        int completedUnits = row.getInt("completed_units");
        int failedUnits = row.getInt("failed_units");
        String jobStatus =
            completedUnits == totalUnits
                ? "review_required"
                : completedUnits + failedUnits == totalUnits ? "failed" : "translating";
        return new JobProgress(totalUnits, completedUnits, failedUnits, jobStatus);
      }
    }
  }

  private static void updateCandidateStatus(Connection connection, UUID candidateId, String status)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_candidates SET status = ?
                WHERE id = ? AND status IN ('assembling', 'partial')
                """)) {
      update.setString(1, status);
      update.setObject(2, candidateId);
      if (update.executeUpdate() != 1) throw new CompletionConflictException();
    }
  }

  private static void updateJobStatus(Connection connection, UUID jobId, JobProgress progress)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_jobs
                SET completed_units = ?, failed_units = ?,
                    status = ?,
                    failure_code = CASE WHEN ? = 'failed' THEN
                        (SELECT last_failure_code FROM translation_units
                         WHERE job_id = ? AND status = 'failed'
                         ORDER BY updated_at DESC, unit_no DESC LIMIT 1)
                        ELSE NULL END,
                    completed_at = CASE WHEN ? IN ('review_required', 'failed')
                        THEN clock_timestamp() ELSE NULL END,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'translating'
                """)) {
      update.setInt(1, progress.completedUnits());
      update.setInt(2, progress.failedUnits());
      update.setString(3, progress.jobStatus());
      update.setString(4, progress.jobStatus());
      update.setObject(5, jobId);
      update.setString(6, progress.jobStatus());
      update.setObject(7, jobId);
      if (update.executeUpdate() != 1) throw new CompletionNotAllowedException();
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

  private static void insertUnitCompletedEvent(
      Connection connection, UUID jobId, UUID unitId, UUID attemptId, int attemptNo)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events
                    (event_id, aggregate_id, event_type, schema_version, deduplication_key,
                     correlation_id, payload)
                VALUES (?, ?, 'translation.unit.completed', 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'unitId', ?::text,
                                           'attemptId', ?::text, 'attemptNo', ?))
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, jobId);
      insert.setString(3, "unit-completed-" + attemptId);
      insert.setObject(4, attemptId);
      insert.setObject(5, jobId);
      insert.setObject(6, unitId);
      insert.setObject(7, attemptId);
      insert.setInt(8, attemptNo);
      insert.executeUpdate();
    }
  }

  private static void insertCandidateReadyEvent(Connection connection, UUID jobId, UUID candidateId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events
                    (event_id, aggregate_id, event_type, schema_version, deduplication_key,
                     correlation_id, payload)
                VALUES (?, ?, 'translation.candidate.ready', 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'candidateId', ?::text))
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, jobId);
      insert.setString(3, "candidate-ready-" + candidateId);
      insert.setObject(4, candidateId);
      insert.setObject(5, jobId);
      insert.setObject(6, candidateId);
      insert.executeUpdate();
    }
  }

  private static Candidate findCompletedCandidate(
      Connection connection,
      UnitContext unit,
      List<SourceSegment> sourceSegments,
      List<TranslatedSegment> translations)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT candidate.id
                FROM translation_candidates candidate
                JOIN translation_candidate_segments translated
                  ON translated.candidate_id = candidate.id
                JOIN translation_unit_segments mapped
                  ON mapped.unit_id = ? AND mapped.source_segment_id = translated.source_segment_id
                WHERE candidate.job_id = ?
                GROUP BY candidate.id
                HAVING count(*) = ?
                ORDER BY candidate.candidate_no DESC
                LIMIT 1
                """)) {
      select.setObject(1, unit.unitId());
      select.setObject(2, unit.jobId());
      select.setInt(3, sourceSegments.size());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new CompletionConflictException();
        UUID candidateId = row.getObject("id", UUID.class);
        verifyCandidateSegmentHashes(connection, candidateId, translations);
        try (PreparedStatement state =
            connection.prepareStatement(
                """
                    SELECT candidate.status, job.status AS job_status
                    FROM translation_candidates candidate
                    JOIN translation_jobs job ON job.id = candidate.job_id
                    WHERE candidate.id = ?
                    """)) {
          state.setObject(1, candidateId);
          try (ResultSet stateRow = state.executeQuery()) {
            if (!stateRow.next()) throw new CompletionConflictException();
            return new Candidate(
                candidateId, stateRow.getString("status"), stateRow.getString("job_status"));
          }
        }
      }
    }
  }

  private static void verifyCandidateSegmentHashes(
      Connection connection, UUID candidateId, List<TranslatedSegment> translations)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT source_segment_id, translated_sha256
                FROM translation_candidate_segments
                WHERE candidate_id = ?
                """)) {
      select.setObject(1, candidateId);
      try (ResultSet rows = select.executeQuery()) {
        Map<UUID, byte[]> stored = new HashMap<>();
        while (rows.next()) {
          stored.put(
              rows.getObject("source_segment_id", UUID.class), rows.getBytes("translated_sha256"));
        }
        for (TranslatedSegment segment : translations) {
          byte[] checksum = stored.get(segment.sourceSegmentId());
          if (checksum == null || !MessageDigest.isEqual(checksum, segment.translatedSha256())) {
            throw new CompletionConflictException();
          }
        }
      }
    }
  }

  private static byte[] responseSha256(List<TranslatedSegment> translations) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, "translation-response-v1");
      for (TranslatedSegment segment : translations) {
        update(digest, segment.sourceSegmentId().toString());
        update(digest, segment.translatedSha256());
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    update(digest, bytes.length);
    digest.update(bytes);
  }

  private static void update(MessageDigest digest, byte[] value) {
    update(digest, value.length);
    digest.update(value);
  }

  private static void update(MessageDigest digest, int value) {
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record UnitContext(
      UUID unitId,
      UUID jobId,
      UUID sourceRevisionId,
      String status,
      String leaseOwner,
      UUID leaseToken,
      java.time.Instant leaseUntil,
      int attemptCount,
      UUID workId,
      UUID chapterId,
      String providerId,
      byte[] sourceSha256) {}

  private record SourceSegment(UUID id, int ordinal) {}

  private record TranslatedSegment(
      UUID sourceSegmentId, int ordinal, String translatedText, byte[] translatedSha256) {}

  private record AttemptContext(
      UUID id,
      int attemptNo,
      String providerId,
      String outcome,
      byte[] requestSha256,
      byte[] responseSha256) {}

  private record Candidate(UUID id, String status, String jobStatus) {}

  private record JobProgress(
      int totalUnits, int completedUnits, int failedUnits, String jobStatus) {}

  public record CompletionResult(
      UUID attemptId,
      UUID jobId,
      UUID unitId,
      UUID candidateId,
      int completedSegments,
      String candidateStatus,
      String jobStatus,
      boolean replayed) {}

  public static final class StaleLeaseException extends RuntimeException {}

  public static final class StaleSourceException extends RuntimeException {}

  public static final class AttemptNotFoundException extends RuntimeException {}

  public static final class InvalidCompletionException extends RuntimeException {}

  public static final class CompletionConflictException extends RuntimeException {}

  public static final class CompletionNotAllowedException extends RuntimeException {}
}
