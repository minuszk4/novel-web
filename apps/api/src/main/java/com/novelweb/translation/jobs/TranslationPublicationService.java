package com.novelweb.translation.jobs;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.library.LibraryCursorCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Owner-only release of accepted human translation snapshots. */
@Service
@Profile("postgres")
public class TranslationPublicationService {

  private static final byte[] REQUEST_DOMAIN =
      "novel-translation-publication-request-v1\0".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ROLLBACK_REQUEST_DOMAIN =
      "novel-translation-rollback-request-v1\0".getBytes(StandardCharsets.UTF_8);
  private static final byte[] IDEMPOTENCY_DOMAIN =
      "novel-translation-publication-key-v1\0".getBytes(StandardCharsets.UTF_8);

  private final DataSource dataSource;
  private final PolicyService policyService;
  private final LibraryCursorCodec cursorCodec;

  public TranslationPublicationService(
      DataSource dataSource, PolicyService policyService, LibraryCursorCodec cursorCodec) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursor codec is required");
  }

  public AcceptedQueue accepted(UUID ownerUserId, UUID workId, String rawCursor, int limit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (limit < 1 || limit > 50) return AcceptedQueue.gated(QueueOutcome.INVALID_LIMIT);
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        PolicyService.RequiredAcceptance policy = policyService.requiredAcceptance(ownerUserId);
        if (policy != PolicyService.RequiredAcceptance.COMPLETE) {
          connection.commit();
          return AcceptedQueue.gated(
              policy == PolicyService.RequiredAcceptance.REQUIRED
                  ? QueueOutcome.POLICY_REQUIRED
                  : QueueOutcome.POLICY_NOT_CONFIGURED);
        }
        LibraryCursorCodec.CursorContext context =
            LibraryCursorCodec.translationPublicationQueueContext(ownerUserId, workId);
        LibraryCursorCodec.Position position;
        try {
          cursorCodec.requireConfigured();
          position = rawCursor == null ? null : cursorCodec.decode(rawCursor, context);
          if (position != null && position.rank() != 0) {
            connection.commit();
            return AcceptedQueue.gated(QueueOutcome.INVALID_CURSOR);
          }
        } catch (LibraryCursorCodec.InvalidCursorException exception) {
          connection.commit();
          return AcceptedQueue.gated(QueueOutcome.INVALID_CURSOR);
        } catch (LibraryCursorCodec.SigningUnavailableException exception) {
          connection.commit();
          return AcceptedQueue.gated(QueueOutcome.CURSOR_UNAVAILABLE);
        }
        List<AcceptedItem> selected = new ArrayList<>();
        String cursorPredicate =
            position == null
                ? ""
                : " AND (submission.submitted_at < ? OR"
                    + " (submission.submitted_at = ? AND submission.id < ?))";
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT submission.id, submission.chapter_id, chapter.title AS chapter_title,
                           work.title AS work_title, submission.target_language,
                           submission.snapshot_sha256, submission.segment_count,
                           submission.submitted_at,
                           publication_scope_access_is_current(
                               submission.work_id, 'TRANSLATION_ADAPTATION') AS rights_current,
                           current_version.id AS current_version_id,
                           current_version.version_no AS current_version_no
                      FROM translation_candidate_review_submissions submission
                      JOIN translation_candidate_review_decisions decision
                        ON decision.submission_id = submission.id
                       AND decision.action_code = 'ACCEPT'
                       AND decision.snapshot_sha256 = submission.snapshot_sha256
                      JOIN works work ON work.id = submission.work_id
                      JOIN chapters chapter
                        ON chapter.id = submission.chapter_id AND chapter.work_id = work.id
                      JOIN chapter_revisions source_revision
                        ON source_revision.id = chapter.source_revision_id
                       AND source_revision.chapter_id = chapter.id
                      LEFT JOIN translation_versions current_version
                        ON current_version.chapter_id = chapter.id
                       AND current_version.target_language = submission.target_language
                       AND current_version.status = 'current'
                     WHERE submission.work_id = ? AND work.owner_user_id = ?
                       AND work.status = 'active' AND work.visibility = 'public'
                       AND work.source_kind = 'authored' AND work.deleted_at IS NULL
                       AND chapter.status = 'ready' AND chapter.deleted_at IS NULL
                       AND chapter.source_revision_id = submission.source_revision_id
                       AND source_revision.content_hash = submission.source_sha256
                       AND submission.status = 'accepted'
                       AND NOT EXISTS (
                           SELECT 1 FROM translation_release_events event
                            WHERE event.review_submission_id = submission.id)
                    """
                    + cursorPredicate
                    + " ORDER BY submission.submitted_at DESC, submission.id DESC LIMIT ?")) {
          int parameterIndex = 1;
          select.setObject(parameterIndex++, workId);
          select.setObject(parameterIndex++, ownerUserId);
          if (position != null) {
            OffsetDateTime submittedAt = position.createdAt().atOffset(java.time.ZoneOffset.UTC);
            select.setObject(parameterIndex++, submittedAt);
            select.setObject(parameterIndex++, submittedAt);
            select.setObject(parameterIndex++, position.itemId());
          }
          select.setInt(parameterIndex, limit + 1);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              selected.add(
                  new AcceptedItem(
                      rows.getObject("id", UUID.class),
                      rows.getObject("chapter_id", UUID.class),
                      rows.getString("work_title"),
                      rows.getString("chapter_title"),
                      rows.getString("target_language"),
                      HexFormat.of().formatHex(rows.getBytes("snapshot_sha256")),
                      rows.getInt("segment_count"),
                      instant(rows, "submitted_at"),
                      rows.getBoolean("rights_current"),
                      rows.getObject("current_version_id", UUID.class),
                      (Integer) rows.getObject("current_version_no")));
            }
          }
        }
        boolean hasMore = selected.size() > limit;
        List<AcceptedItem> items =
            List.copyOf(selected.subList(0, Math.min(limit, selected.size())));
        String nextCursor =
            hasMore
                ? cursorCodec.encode(
                    context,
                    new LibraryCursorCodec.Position(
                        0, items.getLast().submittedAt(), items.getLast().submissionId()))
                : null;
        connection.commit();
        return AcceptedQueue.available(items, nextCursor, hasMore);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load accepted translation snapshots", exception);
    }
  }

  public ReleaseHistory releaseHistory(UUID ownerUserId, UUID workId, String rawCursor, int limit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (limit < 1 || limit > 50) return ReleaseHistory.gated(HistoryOutcome.INVALID_LIMIT);
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        PolicyService.RequiredAcceptance policy = policyService.requiredAcceptance(ownerUserId);
        if (policy != PolicyService.RequiredAcceptance.COMPLETE) {
          connection.commit();
          return ReleaseHistory.gated(
              policy == PolicyService.RequiredAcceptance.REQUIRED
                  ? HistoryOutcome.POLICY_REQUIRED
                  : HistoryOutcome.POLICY_NOT_CONFIGURED);
        }
        LibraryCursorCodec.CursorContext context =
            LibraryCursorCodec.translationReleaseHistoryContext(ownerUserId, workId);
        LibraryCursorCodec.Position position;
        try {
          cursorCodec.requireConfigured();
          position = rawCursor == null ? null : cursorCodec.decode(rawCursor, context);
          if (position != null && position.rank() != 0) {
            connection.commit();
            return ReleaseHistory.gated(HistoryOutcome.INVALID_CURSOR);
          }
        } catch (LibraryCursorCodec.InvalidCursorException exception) {
          connection.commit();
          return ReleaseHistory.gated(HistoryOutcome.INVALID_CURSOR);
        } catch (LibraryCursorCodec.SigningUnavailableException exception) {
          connection.commit();
          return ReleaseHistory.gated(HistoryOutcome.CURSOR_UNAVAILABLE);
        }

        String cursorPredicate =
            position == null
                ? ""
                : " AND (event.released_at < ? OR (event.released_at = ? AND event.id < ?))";
        List<VersionHistoryItem> selected = new ArrayList<>();
        try (PreparedStatement query =
            connection.prepareStatement(
                """
                    SELECT event.id AS event_id, version.id AS version_id, version.version_no,
                           version.target_language,
                           version.rollback_of_version_id, event.action_code, event.released_at,
                           chapter.id AS chapter_id, chapter.title AS chapter_title,
                           chapter.source_revision_id = version.source_revision_id AS source_current,
                           current_version.id AS current_version_id,
                           current_version.version_no AS current_version_no,
                           publication_scope_access_is_current(
                               work.id, 'TRANSLATION_ADAPTATION') AS rights_current
                      FROM translation_release_events event
                      JOIN translation_versions version
                        ON version.id = event.translation_version_id
                      JOIN works work ON work.id = event.work_id
                      JOIN chapters chapter ON chapter.id = event.chapter_id
                      LEFT JOIN translation_versions current_version
                        ON current_version.chapter_id = version.chapter_id
                       AND current_version.target_language = version.target_language
                       AND current_version.status = 'current'
                     WHERE event.work_id = ? AND work.owner_user_id = ?
                       AND work.deleted_at IS NULL
                    """
                    + cursorPredicate
                    + " ORDER BY event.released_at DESC, event.id DESC LIMIT ?")) {
          int index = 1;
          query.setObject(index++, workId);
          query.setObject(index++, ownerUserId);
          if (position != null) {
            OffsetDateTime releasedAt = position.createdAt().atOffset(java.time.ZoneOffset.UTC);
            query.setObject(index++, releasedAt);
            query.setObject(index++, releasedAt);
            query.setObject(index++, position.itemId());
          }
          query.setInt(index, limit + 1);
          try (ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
              UUID currentVersionId = rows.getObject("current_version_id", UUID.class);
              Integer currentVersionNo = (Integer) rows.getObject("current_version_no");
              boolean sourceCurrent = rows.getBoolean("source_current");
              boolean rightsCurrent = rows.getBoolean("rights_current");
              selected.add(
                  new VersionHistoryItem(
                      rows.getObject("event_id", UUID.class),
                      rows.getObject("version_id", UUID.class),
                      rows.getObject("chapter_id", UUID.class),
                      rows.getString("chapter_title"),
                      rows.getString("target_language"),
                      rows.getInt("version_no"),
                      rows.getString("action_code"),
                      rows.getObject("rollback_of_version_id", UUID.class),
                      instant(rows, "released_at"),
                      currentVersionId != null
                          && currentVersionId.equals(rows.getObject("version_id", UUID.class)),
                      currentVersionId,
                      currentVersionNo,
                      currentVersionId == null
                          ? null
                          : versionEtag(currentVersionId, currentVersionNo),
                      sourceCurrent
                          && rightsCurrent
                          && currentVersionId != null
                          && !currentVersionId.equals(rows.getObject("version_id", UUID.class))));
            }
          }
        }
        boolean hasMore = selected.size() > limit;
        List<VersionHistoryItem> items =
            List.copyOf(selected.subList(0, Math.min(limit, selected.size())));
        String nextCursor =
            hasMore
                ? cursorCodec.encode(
                    context,
                    new LibraryCursorCodec.Position(
                        0, items.getLast().releasedAt(), items.getLast().eventId()))
                : null;
        connection.commit();
        return ReleaseHistory.available(items, nextCursor, hasMore);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load translation release history", exception);
    }
  }

  public ReleaseResult publish(
      UUID ownerUserId, UUID submissionId, String expectedEtag, String idempotencyKey) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(submissionId, "submission id is required");
    Objects.requireNonNull(expectedEtag, "snapshot ETag is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");
    byte[] keyDigest = idempotencyDigest(idempotencyKey);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try {
        Optional<Submission> selected = lockOwnerSubmission(connection, ownerUserId, submissionId);
        if (selected.isEmpty()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.NOT_FOUND);
        }
        Submission submission = selected.orElseThrow();
        byte[] requestDigest = requestDigest(ownerUserId, submissionId, expectedEtag);
        Optional<ReleaseReceipt> replay =
            findReplay(connection, ownerUserId, submission, keyDigest, requestDigest);
        if (replay.isPresent()) {
          connection.commit();
          return ReleaseResult.receipt(ReleaseOutcome.REPLAYED, replay.orElseThrow());
        }
        if (hasPriorRelease(connection, submission.id())) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.ALREADY_RELEASED);
        }
        if (!submission.sourceCurrent()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.STALE_SOURCE);
        }
        if (!submission.accepted() || !submission.acceptedDecision()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.NOT_ACCEPTED);
        }
        String etag = TranslationReviewSnapshotHasher.etag(submission.snapshotSha256());
        if (!etag.equals(expectedEtag)) {
          connection.commit();
          return ReleaseResult.stale(etag);
        }

        try (PreparedStatement lockPolicy =
            connection.prepareStatement("LOCK TABLE policy_documents IN SHARE MODE")) {
          lockPolicy.execute();
        }
        try (PreparedStatement lockRightsPolicy =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lockRightsPolicy.execute();
        }
        PolicyService.RequiredAcceptance policy = policyService.requiredAcceptance(ownerUserId);
        if (policy == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.POLICY_REQUIRED);
        }
        if (policy == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.POLICY_NOT_CONFIGURED);
        }

        Optional<RightsBinding> rights = currentRightsBinding(connection, submission.workId());
        if (rights.isEmpty()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.RIGHTS_REQUIRED);
        }
        if (!snapshotIsExact(connection, submission)) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.SNAPSHOT_UNAVAILABLE);
        }

        int versionNo =
            nextVersionNo(connection, submission.chapterId(), submission.targetLanguage());
        UUID versionId = UUID.randomUUID();
        supersedeCurrentVersion(connection, submission.chapterId(), submission.targetLanguage());
        insertVersion(connection, versionId, versionNo, ownerUserId, submission);
        copyAcceptedSegments(connection, versionId, submission);
        ReleaseReceipt receipt =
            insertReleaseEvent(
                connection,
                UUID.randomUUID(),
                versionId,
                ownerUserId,
                submission,
                rights.orElseThrow(),
                keyDigest,
                requestDigest,
                versionNo);
        connection.commit();
        return ReleaseResult.receipt(ReleaseOutcome.CREATED, receipt);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) {
        throw new IdempotencyConflictException();
      }
      throw new IllegalStateException(
          "Unable to publish the accepted translation snapshot", exception);
    }
  }

  public ReleaseResult rollbackToVersion(
      UUID ownerUserId, UUID targetVersionId, String expectedCurrentEtag, String idempotencyKey) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(targetVersionId, "target version id is required");
    Objects.requireNonNull(expectedCurrentEtag, "current-version ETag is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");
    byte[] keyDigest = idempotencyDigest(idempotencyKey);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try {
        Optional<RollbackTarget> selected =
            lockRollbackTarget(connection, ownerUserId, targetVersionId);
        if (selected.isEmpty()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.NOT_FOUND);
        }
        RollbackTarget target = selected.orElseThrow();
        Optional<CurrentVersion> selectedCurrent =
            lockCurrentVersion(connection, target.chapterId(), target.targetLanguage());
        if (selectedCurrent.isEmpty()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.NO_CURRENT_VERSION);
        }
        CurrentVersion current = selectedCurrent.orElseThrow();
        byte[] requestDigest =
            rollbackRequestDigest(ownerUserId, targetVersionId, expectedCurrentEtag);
        Optional<ReleaseReceipt> replay =
            findReplay(
                connection,
                ownerUserId,
                target.workId(),
                target.chapterId(),
                target.targetLanguage(),
                keyDigest,
                requestDigest);
        if (replay.isPresent()) {
          connection.commit();
          return ReleaseResult.receipt(ReleaseOutcome.REPLAYED, replay.orElseThrow());
        }
        String currentEtag = versionEtag(current.id(), current.versionNo());
        if (!currentEtag.equals(expectedCurrentEtag)) {
          connection.commit();
          return ReleaseResult.stale(currentEtag);
        }
        if (!target.superseded()
            || target.versionId().equals(current.id())
            || !target.sourceCurrent()
            || !target.hasReleaseEvent()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.ROLLBACK_TARGET_UNAVAILABLE);
        }

        try (PreparedStatement lockPolicy =
            connection.prepareStatement("LOCK TABLE policy_documents IN SHARE MODE")) {
          lockPolicy.execute();
        }
        try (PreparedStatement lockRightsPolicy =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lockRightsPolicy.execute();
        }
        PolicyService.RequiredAcceptance policy = policyService.requiredAcceptance(ownerUserId);
        if (policy == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.POLICY_REQUIRED);
        }
        if (policy == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.POLICY_NOT_CONFIGURED);
        }
        Optional<RightsBinding> rights = currentRightsBinding(connection, target.workId());
        if (rights.isEmpty()) {
          connection.commit();
          return ReleaseResult.of(ReleaseOutcome.RIGHTS_REQUIRED);
        }

        int versionNo = nextVersionNo(connection, target.chapterId(), target.targetLanguage());
        UUID versionId = UUID.randomUUID();
        if (supersedeVersion(connection, current.id()) != 1) {
          throw new SQLException("Current translation version changed during rollback", "40001");
        }
        insertRollbackVersion(connection, versionId, versionNo, ownerUserId, target);
        copyRetainedSegments(connection, versionId, target.versionId());
        ReleaseReceipt receipt =
            insertRollbackReleaseEvent(
                connection,
                UUID.randomUUID(),
                versionId,
                ownerUserId,
                target,
                rights.orElseThrow(),
                keyDigest,
                requestDigest,
                versionNo);
        connection.commit();
        return ReleaseResult.receipt(ReleaseOutcome.ROLLED_BACK, receipt);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) {
        throw new IdempotencyConflictException();
      }
      throw new IllegalStateException("Unable to roll back the released translation", exception);
    }
  }

  private static Optional<Submission> lockOwnerSubmission(
      Connection connection, UUID ownerUserId, UUID submissionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT submission.id, submission.candidate_id, submission.job_id,
                       submission.work_id, submission.chapter_id, submission.target_language,
                       submission.source_revision_id, submission.source_sha256,
                       submission.snapshot_sha256, submission.segment_count,
                       submission.status, job.source_language,
                       (decision.action_code = 'ACCEPT'
                         AND decision.snapshot_sha256 = submission.snapshot_sha256) AS accepted,
                       (chapter.status = 'ready'
                         AND chapter.deleted_at IS NULL
                         AND chapter.source_revision_id = submission.source_revision_id
                         AND source_revision.content_hash = submission.source_sha256
                         AND work.status = 'active' AND work.deleted_at IS NULL
                         AND work.visibility = 'public' AND work.source_kind = 'authored'
                         AND owner.status = 'active') AS source_current
                  FROM translation_candidate_review_submissions submission
                  JOIN translation_jobs job ON job.id = submission.job_id
                  JOIN works work ON work.id = submission.work_id
                  JOIN users owner ON owner.id = work.owner_user_id
                  JOIN chapters chapter
                    ON chapter.id = submission.chapter_id AND chapter.work_id = work.id
                  JOIN chapter_revisions source_revision
                    ON source_revision.id = submission.source_revision_id
                   AND source_revision.chapter_id = chapter.id
                  LEFT JOIN translation_candidate_review_decisions decision
                    ON decision.submission_id = submission.id
                 WHERE submission.id = ? AND work.owner_user_id = ?
                 FOR UPDATE OF submission, work, chapter
                """)) {
      select.setObject(1, submissionId);
      select.setObject(2, ownerUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new Submission(
                row.getObject("id", UUID.class),
                row.getObject("candidate_id", UUID.class),
                row.getObject("job_id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getObject("chapter_id", UUID.class),
                row.getString("target_language"),
                row.getObject("source_revision_id", UUID.class),
                row.getString("source_language"),
                row.getBytes("source_sha256"),
                row.getBytes("snapshot_sha256"),
                row.getInt("segment_count"),
                row.getString("status").equals("accepted"),
                row.getBoolean("accepted"),
                row.getBoolean("source_current")));
      }
    }
  }

  private static Optional<RollbackTarget> lockRollbackTarget(
      Connection connection, UUID ownerUserId, UUID targetVersionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT target.id, target.work_id, target.chapter_id, target.target_language,
                       target.source_revision_id, target.candidate_id, target.review_submission_id,
                       target.review_snapshot_sha256, target.status,
                       (chapter.status = 'ready' AND chapter.deleted_at IS NULL
                        AND chapter.source_revision_id = target.source_revision_id
                        AND work.status = 'active' AND work.deleted_at IS NULL
                        AND work.visibility = 'public' AND work.source_kind = 'authored'
                        AND owner.status = 'active') AS source_current,
                       EXISTS (SELECT 1 FROM translation_release_events event
                                WHERE event.translation_version_id = target.id) AS released
                  FROM translation_versions target
                  JOIN works work ON work.id = target.work_id
                  JOIN users owner ON owner.id = work.owner_user_id
                  JOIN chapters chapter ON chapter.id = target.chapter_id
                 WHERE target.id = ? AND work.owner_user_id = ?
                 FOR UPDATE OF target, work, chapter
                """)) {
      select.setObject(1, targetVersionId);
      select.setObject(2, ownerUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new RollbackTarget(
                row.getObject("id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getObject("chapter_id", UUID.class),
                row.getString("target_language"),
                row.getObject("source_revision_id", UUID.class),
                row.getObject("candidate_id", UUID.class),
                row.getObject("review_submission_id", UUID.class),
                row.getBytes("review_snapshot_sha256"),
                row.getString("status").equals("superseded"),
                row.getBoolean("source_current"),
                row.getBoolean("released")));
      }
    }
  }

  private static Optional<CurrentVersion> lockCurrentVersion(
      Connection connection, UUID chapterId, String targetLanguage) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id, version_no FROM translation_versions "
                + "WHERE chapter_id = ? AND target_language = ? AND status = 'current' FOR UPDATE")) {
      select.setObject(1, chapterId);
      select.setString(2, targetLanguage);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new CurrentVersion(row.getObject("id", UUID.class), row.getInt("version_no")))
            : Optional.empty();
      }
    }
  }

  private static Optional<ReleaseReceipt> findReplay(
      Connection connection,
      UUID ownerUserId,
      UUID workId,
      UUID chapterId,
      String targetLanguage,
      byte[] keyDigest,
      byte[] requestDigest)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event.request_sha256, version.id, version.version_no,
                       event.review_snapshot_sha256, event.released_at
                  FROM translation_release_events event
                  JOIN translation_versions version ON version.id = event.translation_version_id
                 WHERE event.owner_user_id = ? AND event.work_id = ? AND event.chapter_id = ?
                   AND event.target_language = ? AND event.idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, ownerUserId);
      select.setObject(2, workId);
      select.setObject(3, chapterId);
      select.setString(4, targetLanguage);
      select.setBytes(5, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        if (!MessageDigest.isEqual(requestDigest, row.getBytes("request_sha256"))) {
          throw new IdempotencyConflictException();
        }
        return Optional.of(
            new ReleaseReceipt(
                row.getObject("id", UUID.class),
                row.getInt("version_no"),
                HexFormat.of().formatHex(row.getBytes("review_snapshot_sha256")),
                instant(row, "released_at"),
                true));
      }
    }
  }

  private static boolean hasPriorRelease(Connection connection, UUID submissionId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT 1 FROM translation_release_events WHERE review_submission_id = ? LIMIT 1")) {
      select.setObject(1, submissionId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static int supersedeVersion(Connection connection, UUID currentVersionId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE translation_versions SET status = 'superseded', "
                + "superseded_at = clock_timestamp() WHERE id = ? AND status = 'current'")) {
      update.setObject(1, currentVersionId);
      return update.executeUpdate();
    }
  }

  private static void insertRollbackVersion(
      Connection connection, UUID versionId, int versionNo, UUID ownerUserId, RollbackTarget target)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_versions
                    (id, work_id, chapter_id, target_language, source_revision_id, candidate_id,
                     version_no, status, created_by_user_id, review_submission_id,
                     review_snapshot_sha256, rollback_of_version_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'current', ?, ?, ?, ?)
                """)) {
      insert.setObject(1, versionId);
      insert.setObject(2, target.workId());
      insert.setObject(3, target.chapterId());
      insert.setString(4, target.targetLanguage());
      insert.setObject(5, target.sourceRevisionId());
      insert.setObject(6, target.candidateId());
      insert.setInt(7, versionNo);
      insert.setObject(8, ownerUserId);
      insert.setObject(9, target.reviewSubmissionId());
      insert.setBytes(10, target.snapshotSha256());
      insert.setObject(11, target.versionId());
      insert.executeUpdate();
    }
  }

  private static void copyRetainedSegments(
      Connection connection, UUID newVersionId, UUID targetVersionId) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_version_segments
                    (translation_version_id, source_revision_id, source_segment_id,
                     segment_ordinal, segment_type, stable_anchor, translated_text,
                     translated_sha256)
                SELECT ?, source_revision_id, source_segment_id, segment_ordinal, segment_type,
                       stable_anchor, translated_text, translated_sha256
                  FROM translation_version_segments
                 WHERE translation_version_id = ?
                """)) {
      insert.setObject(1, newVersionId);
      insert.setObject(2, targetVersionId);
      if (insert.executeUpdate() < 1) {
        throw new SQLException("Retained translation content is unavailable", "23514");
      }
    }
  }

  private static ReleaseReceipt insertRollbackReleaseEvent(
      Connection connection,
      UUID eventId,
      UUID versionId,
      UUID ownerUserId,
      RollbackTarget target,
      RightsBinding rights,
      byte[] keyDigest,
      byte[] requestDigest,
      int versionNo)
      throws SQLException {
    Instant releasedAt;
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_release_events
                    (id, translation_version_id, review_submission_id, candidate_id,
                     review_snapshot_sha256, owner_user_id, work_id, chapter_id, target_language,
                     source_revision_id, action_code, rights_approval_id,
                     rights_snapshot_sha256, policy_version, policy_sha256,
                     idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ROLLBACK', ?, ?, ?, ?, ?, ?)
                RETURNING released_at
                """)) {
      insert.setObject(1, eventId);
      insert.setObject(2, versionId);
      insert.setObject(3, target.reviewSubmissionId());
      insert.setObject(4, target.candidateId());
      insert.setBytes(5, target.snapshotSha256());
      insert.setObject(6, ownerUserId);
      insert.setObject(7, target.workId());
      insert.setObject(8, target.chapterId());
      insert.setString(9, target.targetLanguage());
      insert.setObject(10, target.sourceRevisionId());
      insert.setObject(11, rights.approvalId());
      insert.setBytes(12, rights.snapshotSha256());
      insert.setString(13, rights.policyVersion());
      insert.setBytes(14, rights.policySha256());
      insert.setBytes(15, keyDigest);
      insert.setBytes(16, requestDigest);
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        releasedAt = instant(row, "released_at");
      }
    }
    return new ReleaseReceipt(
        versionId, versionNo, HexFormat.of().formatHex(target.snapshotSha256()), releasedAt, false);
  }

  private static Optional<ReleaseReceipt> findReplay(
      Connection connection,
      UUID ownerUserId,
      Submission submission,
      byte[] keyDigest,
      byte[] requestDigest)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event.request_sha256, version.id, version.version_no,
                       event.review_snapshot_sha256, event.released_at
                  FROM translation_release_events event
                  JOIN translation_versions version
                    ON version.id = event.translation_version_id
                 WHERE event.owner_user_id = ? AND event.work_id = ? AND event.chapter_id = ?
                   AND event.target_language = ? AND event.idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, ownerUserId);
      select.setObject(2, submission.workId());
      select.setObject(3, submission.chapterId());
      select.setString(4, submission.targetLanguage());
      select.setBytes(5, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        if (!MessageDigest.isEqual(requestDigest, row.getBytes("request_sha256"))) {
          throw new IdempotencyConflictException();
        }
        return Optional.of(
            new ReleaseReceipt(
                row.getObject("id", UUID.class),
                row.getInt("version_no"),
                HexFormat.of().formatHex(row.getBytes("review_snapshot_sha256")),
                instant(row, "released_at"),
                true));
      }
    }
  }

  private static Optional<RightsBinding> currentRightsBinding(Connection connection, UUID workId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT approval.id, approval.approved_snapshot::text AS approved_snapshot,
                       approval.policy_version, approval.policy_sha256,
                       policy.version AS current_policy_version,
                       policy.policy_sha256 AS current_policy_sha256
                  FROM publication_scope_access_approvals approval
                  JOIN publication_rights_policy_documents policy
                    ON policy.status = 'APPROVED' AND policy.effective_at <= clock_timestamp()
                 WHERE approval.work_id = ? AND approval.scope_code = 'TRANSLATION_ADAPTATION'
                   AND approval.approved_snapshot = publication_work_scope_snapshot(
                       approval.work_id, 'TRANSLATION_ADAPTATION')
                   AND approval.policy_version = policy.version
                   AND approval.policy_sha256 = policy.policy_sha256
                   AND NOT EXISTS (
                       SELECT 1 FROM publication_scope_access_revocations revocation
                        WHERE revocation.approval_id = approval.id)
                   AND (approval.expires_at IS NULL OR approval.expires_at > clock_timestamp())
                   AND publication_scope_access_is_current(
                       approval.work_id, 'TRANSLATION_ADAPTATION')
                 ORDER BY policy.effective_at DESC, policy.version DESC,
                          approval.approved_at DESC, approval.id DESC
                 LIMIT 1
                 FOR UPDATE OF approval
                """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        byte[] policySha256 = row.getBytes("policy_sha256");
        if (!MessageDigest.isEqual(policySha256, row.getBytes("current_policy_sha256"))) {
          return Optional.empty();
        }
        return Optional.of(
            new RightsBinding(
                row.getObject("id", UUID.class),
                sha256(row.getString("approved_snapshot")),
                row.getString("policy_version"),
                policySha256));
      }
    }
  }

  private static boolean snapshotIsExact(Connection connection, Submission submission)
      throws SQLException {
    List<TranslationCandidateReviewRepository.SnapshotSegment> segments = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT snapshot_segment.source_segment_id, snapshot_segment.segment_ordinal,
                       snapshot_segment.edit_revision, snapshot_segment.translated_sha256,
                       edit.translated_text, source_segment.segment_type,
                       source_segment.stable_anchor
                  FROM translation_candidate_review_submission_segments snapshot_segment
                  JOIN translation_candidate_segment_edits edit
                    ON edit.candidate_id = snapshot_segment.candidate_id
                   AND edit.source_segment_id = snapshot_segment.source_segment_id
                   AND edit.edit_revision = snapshot_segment.edit_revision
                  JOIN segments source_segment
                    ON source_segment.id = snapshot_segment.source_segment_id
                   AND source_segment.chapter_revision_id = ?
                 WHERE snapshot_segment.submission_id = ?
                 ORDER BY snapshot_segment.segment_ordinal, snapshot_segment.source_segment_id
                """)) {
      select.setObject(1, submission.sourceRevisionId());
      select.setObject(2, submission.id());
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          byte[] expectedTextDigest = sha256(rows.getString("translated_text"));
          byte[] storedTextDigest = rows.getBytes("translated_sha256");
          if (!MessageDigest.isEqual(expectedTextDigest, storedTextDigest)) return false;
          segments.add(
              new TranslationCandidateReviewRepository.SnapshotSegment(
                  rows.getObject("source_segment_id", UUID.class),
                  rows.getInt("segment_ordinal"),
                  rows.getInt("edit_revision"),
                  storedTextDigest,
                  !rows.getString("translated_text").isBlank()));
        }
      }
    }
    if (segments.size() != submission.segmentCount()
        || segments.stream().anyMatch(segment -> !segment.hasManualEdit())) return false;
    byte[] actualDigest =
        TranslationReviewSnapshotHasher.snapshotDigest(
            new TranslationReviewSnapshotHasher.SnapshotContext(
                submission.candidateId(),
                submission.jobId(),
                submission.workId(),
                submission.chapterId(),
                submission.sourceRevisionId(),
                submission.sourceLanguage(),
                submission.targetLanguage(),
                submission.sourceSha256()),
            segments);
    return MessageDigest.isEqual(actualDigest, submission.snapshotSha256());
  }

  private static int nextVersionNo(Connection connection, UUID chapterId, String language)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(version_no), 0) + 1 FROM translation_versions "
                + "WHERE chapter_id = ? AND target_language = ?")) {
      select.setObject(1, chapterId);
      select.setString(2, language);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private static void supersedeCurrentVersion(
      Connection connection, UUID chapterId, String language) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE translation_versions SET status = 'superseded', "
                + "superseded_at = clock_timestamp() WHERE chapter_id = ? "
                + "AND target_language = ? AND status = 'current'")) {
      update.setObject(1, chapterId);
      update.setString(2, language);
      update.executeUpdate();
    }
  }

  private static void insertVersion(
      Connection connection, UUID versionId, int versionNo, UUID ownerUserId, Submission submission)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_versions
                    (id, work_id, chapter_id, target_language, source_revision_id, candidate_id,
                     version_no, status, created_by_user_id, review_submission_id,
                     review_snapshot_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'current', ?, ?, ?)
                """)) {
      insert.setObject(1, versionId);
      insert.setObject(2, submission.workId());
      insert.setObject(3, submission.chapterId());
      insert.setString(4, submission.targetLanguage());
      insert.setObject(5, submission.sourceRevisionId());
      insert.setObject(6, submission.candidateId());
      insert.setInt(7, versionNo);
      insert.setObject(8, ownerUserId);
      insert.setObject(9, submission.id());
      insert.setBytes(10, submission.snapshotSha256());
      insert.executeUpdate();
    }
  }

  private static void copyAcceptedSegments(
      Connection connection, UUID versionId, Submission submission) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_version_segments
                    (translation_version_id, source_revision_id, source_segment_id,
                     segment_ordinal, segment_type, stable_anchor, translated_text,
                     translated_sha256)
                SELECT ?, source_segment.chapter_revision_id, source_segment.id,
                       snapshot_segment.segment_ordinal, source_segment.segment_type,
                       source_segment.stable_anchor, edit.translated_text,
                       snapshot_segment.translated_sha256
                  FROM translation_candidate_review_submission_segments snapshot_segment
                  JOIN translation_candidate_segment_edits edit
                    ON edit.candidate_id = snapshot_segment.candidate_id
                   AND edit.source_segment_id = snapshot_segment.source_segment_id
                   AND edit.edit_revision = snapshot_segment.edit_revision
                  JOIN segments source_segment
                    ON source_segment.id = snapshot_segment.source_segment_id
                   AND source_segment.chapter_revision_id = ?
                 WHERE snapshot_segment.submission_id = ?
                """)) {
      insert.setObject(1, versionId);
      insert.setObject(2, submission.sourceRevisionId());
      insert.setObject(3, submission.id());
      if (insert.executeUpdate() != submission.segmentCount()) {
        throw new SQLException("Accepted translation snapshot segment coverage changed", "23514");
      }
    }
  }

  private static ReleaseReceipt insertReleaseEvent(
      Connection connection,
      UUID eventId,
      UUID versionId,
      UUID ownerUserId,
      Submission submission,
      RightsBinding rights,
      byte[] keyDigest,
      byte[] requestDigest,
      int versionNo)
      throws SQLException {
    Instant releasedAt;
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_release_events
                    (id, translation_version_id, review_submission_id, candidate_id,
                     review_snapshot_sha256, owner_user_id, work_id, chapter_id, target_language,
                     source_revision_id, action_code, rights_approval_id,
                     rights_snapshot_sha256, policy_version, policy_sha256,
                     idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLISH', ?, ?, ?, ?, ?, ?)
                RETURNING released_at
                """)) {
      insert.setObject(1, eventId);
      insert.setObject(2, versionId);
      insert.setObject(3, submission.id());
      insert.setObject(4, submission.candidateId());
      insert.setBytes(5, submission.snapshotSha256());
      insert.setObject(6, ownerUserId);
      insert.setObject(7, submission.workId());
      insert.setObject(8, submission.chapterId());
      insert.setString(9, submission.targetLanguage());
      insert.setObject(10, submission.sourceRevisionId());
      insert.setObject(11, rights.approvalId());
      insert.setBytes(12, rights.snapshotSha256());
      insert.setString(13, rights.policyVersion());
      insert.setBytes(14, rights.policySha256());
      insert.setBytes(15, keyDigest);
      insert.setBytes(16, requestDigest);
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        releasedAt = instant(row, "released_at");
      }
    }
    return new ReleaseReceipt(
        versionId,
        versionNo,
        HexFormat.of().formatHex(submission.snapshotSha256()),
        releasedAt,
        false);
  }

  private static byte[] requestDigest(UUID owner, UUID submissionId, String etag) {
    return digest(
        REQUEST_DOMAIN,
        output -> {
          writeUuid(output, owner);
          writeUuid(output, submissionId);
          writeString(output, etag);
        });
  }

  private static byte[] rollbackRequestDigest(UUID owner, UUID targetVersionId, String etag) {
    return digest(
        ROLLBACK_REQUEST_DOMAIN,
        output -> {
          writeUuid(output, owner);
          writeUuid(output, targetVersionId);
          writeString(output, etag);
        });
  }

  public static String versionEtag(UUID versionId, int versionNo) {
    return "\"translation-version-" + versionId + "-" + versionNo + "\"";
  }

  private static byte[] idempotencyDigest(String key) {
    return digest(
        IDEMPOTENCY_DOMAIN, output -> output.write(key.getBytes(StandardCharsets.US_ASCII)));
  }

  private static byte[] digest(byte[] domain, DigestWriter writer) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.write(domain);
        writer.write(output);
      }
      return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
          "Unable to calculate a translation release digest", exception);
    }
  }

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  @FunctionalInterface
  private interface DigestWriter {
    void write(DataOutputStream output) throws IOException;
  }

  private record Submission(
      UUID id,
      UUID candidateId,
      UUID jobId,
      UUID workId,
      UUID chapterId,
      String targetLanguage,
      UUID sourceRevisionId,
      String sourceLanguage,
      byte[] sourceSha256,
      byte[] snapshotSha256,
      int segmentCount,
      boolean accepted,
      boolean acceptedDecision,
      boolean sourceCurrent) {
    private Submission {
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
  }

  private record RightsBinding(
      UUID approvalId, byte[] snapshotSha256, String policyVersion, byte[] policySha256) {
    private RightsBinding {
      snapshotSha256 = snapshotSha256.clone();
      policySha256 = policySha256.clone();
    }

    @Override
    public byte[] snapshotSha256() {
      return snapshotSha256.clone();
    }

    @Override
    public byte[] policySha256() {
      return policySha256.clone();
    }
  }

  public record AcceptedItem(
      UUID submissionId,
      UUID chapterId,
      String workTitle,
      String chapterTitle,
      String targetLanguage,
      String snapshotDigest,
      int segmentCount,
      Instant submittedAt,
      boolean rightsCurrent,
      UUID currentVersionId,
      Integer currentVersionNo) {}

  public record VersionHistoryItem(
      UUID eventId,
      UUID versionId,
      UUID chapterId,
      String chapterTitle,
      String targetLanguage,
      int versionNo,
      String actionCode,
      UUID rollbackOfVersionId,
      Instant releasedAt,
      boolean current,
      UUID currentVersionId,
      Integer currentVersionNo,
      String currentVersionEtag,
      boolean rollbackAllowed) {}

  public record ReleaseHistory(
      HistoryOutcome outcome, List<VersionHistoryItem> items, String nextCursor, boolean hasMore) {
    public ReleaseHistory {
      items = List.copyOf(items);
    }

    private static ReleaseHistory available(
        List<VersionHistoryItem> items, String nextCursor, boolean hasMore) {
      return new ReleaseHistory(HistoryOutcome.AVAILABLE, items, nextCursor, hasMore);
    }

    private static ReleaseHistory gated(HistoryOutcome outcome) {
      return new ReleaseHistory(outcome, List.of(), null, false);
    }
  }

  public enum HistoryOutcome {
    AVAILABLE,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    INVALID_LIMIT,
    INVALID_CURSOR,
    CURSOR_UNAVAILABLE
  }

  public record AcceptedQueue(
      QueueOutcome outcome, List<AcceptedItem> items, String nextCursor, boolean hasMore) {
    public AcceptedQueue {
      items = List.copyOf(items);
    }

    private static AcceptedQueue available(
        List<AcceptedItem> items, String nextCursor, boolean hasMore) {
      return new AcceptedQueue(QueueOutcome.AVAILABLE, items, nextCursor, hasMore);
    }

    private static AcceptedQueue gated(QueueOutcome outcome) {
      return new AcceptedQueue(outcome, List.of(), null, false);
    }
  }

  public enum QueueOutcome {
    AVAILABLE,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    INVALID_LIMIT,
    INVALID_CURSOR,
    CURSOR_UNAVAILABLE
  }

  public enum ReleaseOutcome {
    CREATED,
    ROLLED_BACK,
    REPLAYED,
    NOT_FOUND,
    NOT_ACCEPTED,
    STALE_SOURCE,
    STALE_ETAG,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    RIGHTS_REQUIRED,
    SNAPSHOT_UNAVAILABLE,
    ALREADY_RELEASED,
    NO_CURRENT_VERSION,
    ROLLBACK_TARGET_UNAVAILABLE,
    KEY_REUSED
  }

  public record ReleaseReceipt(
      UUID versionId, int versionNo, String snapshotDigest, Instant releasedAt, boolean replayed) {}

  public record ReleaseResult(ReleaseOutcome outcome, ReleaseReceipt receipt, String currentEtag) {
    private static ReleaseResult of(ReleaseOutcome outcome) {
      return new ReleaseResult(outcome, null, null);
    }

    private static ReleaseResult receipt(ReleaseOutcome outcome, ReleaseReceipt receipt) {
      return new ReleaseResult(outcome, receipt, null);
    }

    private static ReleaseResult stale(String currentEtag) {
      return new ReleaseResult(ReleaseOutcome.STALE_ETAG, null, currentEtag);
    }
  }

  public static final class IdempotencyConflictException extends RuntimeException {}

  private record CurrentVersion(UUID id, int versionNo) {}

  private record RollbackTarget(
      UUID versionId,
      UUID workId,
      UUID chapterId,
      String targetLanguage,
      UUID sourceRevisionId,
      UUID candidateId,
      UUID reviewSubmissionId,
      byte[] snapshotSha256,
      boolean superseded,
      boolean sourceCurrent,
      boolean hasReleaseEvent) {
    private RollbackTarget {
      snapshotSha256 = snapshotSha256.clone();
    }

    @Override
    public byte[] snapshotSha256() {
      return snapshotSha256.clone();
    }
  }
}
