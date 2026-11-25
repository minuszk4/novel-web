package com.novelweb.library.publication;

import com.novelweb.identity.auth.PolicyService;
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

/** Publishes an owner-authored work only after current reviewer approval and rights checks. */
@Service
@Profile("postgres")
public class PublicationReleaseService {

  private static final String KEY_DOMAIN = "publication-release-key-v1";
  private static final String REQUEST_DOMAIN = "publication-release-request-v1";

  private final DataSource dataSource;
  private final PolicyService policyService;

  public PublicationReleaseService(DataSource dataSource, PolicyService policyService) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
  }

  public ReleaseResult publishNow(
      UUID actorId, UUID workId, String ifMatch, String idempotencyKey) {
    Objects.requireNonNull(actorId, "actor id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(ifMatch, "If-Match is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");

    PolicyService.RequiredAcceptance initialAcceptance = policyService.requiredAcceptance(actorId);
    if (initialAcceptance != PolicyService.RequiredAcceptance.COMPLETE) {
      return initialAcceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED
          ? ReleaseResult.policyNotConfigured()
          : ReleaseResult.policyRequired();
    }

    byte[] keyDigest = keyDigest(actorId, workId, idempotencyKey);
    byte[] requestDigest = requestDigest(actorId, workId, ifMatch);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<ReleaseReceipt> previous = findReceipt(connection, actorId, workId, keyDigest);
        if (previous.isPresent()) {
          ReleaseReceipt receipt = previous.orElseThrow();
          if (!MessageDigest.isEqual(receipt.requestSha256(), requestDigest)) {
            connection.commit();
            return ReleaseResult.keyReused();
          }
          connection.commit();
          return ReleaseResult.replayed(receipt);
        }

        Optional<LockedWork> lockedWork = lockEligibleWork(connection, actorId, workId);
        if (lockedWork.isEmpty()) {
          boolean alreadyPublic = isOwnedPublicWork(connection, actorId, workId);
          connection.commit();
          return alreadyPublic ? ReleaseResult.alreadyPublished() : ReleaseResult.workNotFound();
        }
        LockedWork work = lockedWork.orElseThrow();

        Optional<UUID> latestRequestId = latestApprovedRequestId(connection, workId);
        if (latestRequestId.isEmpty()) {
          connection.commit();
          return ReleaseResult.approvalRequired();
        }
        UUID requestId = latestRequestId.orElseThrow();

        Optional<ReleaseReceipt> priorRelease = findReleaseForRequest(connection, requestId);
        if (priorRelease.isPresent()) {
          connection.commit();
          return ReleaseResult.alreadyPublished();
        }

        // Keep policy selection stable through snapshot validation and the release commit.
        try (PreparedStatement lock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lock.execute();
        }

        PolicyService.RequiredAcceptance acceptanceState =
            policyService.requiredAcceptance(actorId);
        if (acceptanceState == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return ReleaseResult.policyRequired();
        }
        if (acceptanceState == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return ReleaseResult.policyNotConfigured();
        }

        Optional<CurrentPolicy> currentPolicy = readCurrentPolicy(connection);
        if (currentPolicy.isEmpty()) {
          connection.commit();
          return ReleaseResult.rightsPolicyNotConfigured();
        }
        CurrentPolicy policy = currentPolicy.orElseThrow();
        Optional<Instant> acceptedAt =
            findAcceptance(connection, actorId, workId, policy.version(), policy.sha256());
        if (acceptedAt.isEmpty()) {
          connection.commit();
          return ReleaseResult.rightsPolicyNotAccepted();
        }

        ReadinessSnapshot readiness = readReadinessSnapshot(connection, workId);
        String currentEtag = readiness.etag(workId, policy, acceptedAt.orElse(null));
        if (!currentEtag.equals(ifMatch)) {
          connection.commit();
          return ReleaseResult.stale(currentEtag);
        }
        if (!"APPROVED_UNPUBLISHED".equals(readiness.currentRequestState())) {
          connection.commit();
          return ReleaseResult.approvalRequired();
        }

        Optional<ApprovedRequest> approvedRequest = readApprovedRequest(connection, requestId);
        if (approvedRequest.isEmpty()) {
          connection.commit();
          return ReleaseResult.approvalRequired();
        }
        ApprovedRequest request = approvedRequest.orElseThrow();
        if (!policy.version().equals(request.policyVersion())
            || !MessageDigest.isEqual(policy.sha256(), request.policySha256())) {
          connection.commit();
          return ReleaseResult.rightsPolicyStale();
        }

        List<String> approvedScopes = currentApprovedScopes(connection, request);
        if (!approvedScopes.contains("PUBLIC_METADATA")
            || !approvedScopes.contains("CHAPTER_TEXT")) {
          connection.commit();
          return ReleaseResult.approvalRequired();
        }

        ReleaseReceipt receipt =
            insertReleaseEvent(
                connection,
                actorId,
                work,
                request,
                keyDigest,
                requestDigest,
                readiness.contentVersion());
        connection.commit();
        return ReleaseResult.created(receipt);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        if (exception instanceof SQLException sqlException
            && "23514".equals(sqlException.getSQLState())) {
          PublicationFailureLog.guardRejected(
              PublicationFailureLog.Operation.RELEASE, sqlException);
          return ReleaseResult.approvalChanged();
        }
        throw exception;
      }
    } catch (SQLException exception) {
      PublicationFailureLog.transactionFailed(PublicationFailureLog.Operation.RELEASE, exception);
      throw new IllegalStateException("Unable to publish the approved manuscript", exception);
    }
  }

  public Optional<UUID> findReleaseEventIdForRequest(UUID requestId) {
    Objects.requireNonNull(requestId, "request id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT id FROM publication_release_events WHERE request_id = ?")) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getObject("id", UUID.class)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read publication release receipt", exception);
    }
  }

  /** Queues an owner-confirmed release; publication itself remains private until the due worker. */
  public ScheduleResult schedule(
      UUID actorId, UUID workId, String ifMatch, Instant scheduledAt, String idempotencyKey) {
    Objects.requireNonNull(actorId, "actor id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(ifMatch, "If-Match is required");
    Objects.requireNonNull(scheduledAt, "scheduled instant is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");

    PolicyService.RequiredAcceptance initialAcceptance = policyService.requiredAcceptance(actorId);
    if (initialAcceptance != PolicyService.RequiredAcceptance.COMPLETE) {
      return initialAcceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED
          ? ScheduleResult.policyNotConfigured()
          : ScheduleResult.policyRequired();
    }

    byte[] keyDigest = scheduleKeyDigest(actorId, workId, idempotencyKey);
    byte[] requestDigest = scheduleRequestDigest(actorId, workId, ifMatch, scheduledAt);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<ScheduleReceipt> previous =
            findScheduleReceipt(connection, actorId, workId, keyDigest);
        if (previous.isPresent()) {
          ScheduleReceipt receipt = previous.orElseThrow();
          if (!MessageDigest.isEqual(receipt.requestSha256(), requestDigest)) {
            connection.commit();
            return ScheduleResult.keyReused();
          }
          connection.commit();
          return ScheduleResult.replayed(receipt);
        }

        if (!scheduledAt.isAfter(Instant.now())) {
          connection.commit();
          return ScheduleResult.timeInvalid();
        }

        Optional<LockedWork> lockedWork = lockEligibleWork(connection, actorId, workId);
        if (lockedWork.isEmpty()) {
          boolean alreadyPublic = isOwnedPublicWork(connection, actorId, workId);
          connection.commit();
          return alreadyPublic ? ScheduleResult.alreadyReleased() : ScheduleResult.workNotFound();
        }
        LockedWork work = lockedWork.orElseThrow();

        Optional<UUID> latestRequestId = latestApprovedRequestId(connection, workId);
        if (latestRequestId.isEmpty()) {
          connection.commit();
          return ScheduleResult.approvalRequired();
        }
        UUID requestId = latestRequestId.orElseThrow();
        String currentRequestState = currentRequestState(connection, requestId);
        if (!"APPROVED_UNPUBLISHED".equals(currentRequestState)) {
          connection.commit();
          return ScheduleResult.approvalRequired();
        }
        if (findActiveScheduleForRequest(connection, requestId).isPresent()) {
          connection.commit();
          return ScheduleResult.alreadyScheduled();
        }
        if (findReleaseForRequest(connection, requestId).isPresent()) {
          connection.commit();
          return ScheduleResult.alreadyReleased();
        }

        try (PreparedStatement lock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lock.execute();
        }

        PolicyService.RequiredAcceptance acceptanceState =
            policyService.requiredAcceptance(actorId);
        if (acceptanceState == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return ScheduleResult.policyRequired();
        }
        if (acceptanceState == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return ScheduleResult.policyNotConfigured();
        }

        Optional<CurrentPolicy> currentPolicy = readCurrentPolicy(connection);
        if (currentPolicy.isEmpty()) {
          connection.commit();
          return ScheduleResult.rightsPolicyNotConfigured();
        }
        CurrentPolicy policy = currentPolicy.orElseThrow();
        Optional<Instant> acceptedAt =
            findAcceptance(connection, actorId, workId, policy.version(), policy.sha256());
        if (acceptedAt.isEmpty()) {
          connection.commit();
          return ScheduleResult.rightsPolicyNotAccepted();
        }

        ReadinessSnapshot readiness = readReadinessSnapshot(connection, workId);
        String currentEtag = readiness.etag(workId, policy, acceptedAt.orElse(null));
        if (!currentEtag.equals(ifMatch)) {
          connection.commit();
          return ScheduleResult.stale(currentEtag);
        }

        Optional<ApprovedRequest> approvedRequest = readApprovedRequest(connection, requestId);
        if (approvedRequest.isEmpty()) {
          connection.commit();
          return ScheduleResult.approvalRequired();
        }
        ApprovedRequest request = approvedRequest.orElseThrow();
        if (!policy.version().equals(request.policyVersion())
            || !MessageDigest.isEqual(policy.sha256(), request.policySha256())) {
          connection.commit();
          return ScheduleResult.rightsPolicyStale();
        }
        List<String> approvedScopes = currentApprovedScopes(connection, request);
        if (!approvedScopes.contains("PUBLIC_METADATA")
            || !approvedScopes.contains("CHAPTER_TEXT")) {
          connection.commit();
          return ScheduleResult.approvalRequired();
        }

        ScheduleReceipt receipt =
            insertSchedule(
                connection, actorId, work, request, ifMatch, scheduledAt, keyDigest, requestDigest);
        connection.commit();
        return ScheduleResult.created(receipt);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        if (exception instanceof SQLException sqlException
            && ("23514".equals(sqlException.getSQLState())
                || "23505".equals(sqlException.getSQLState()))) {
          PublicationFailureLog.guardRejected(
              PublicationFailureLog.Operation.SCHEDULE, sqlException);
          return ScheduleResult.approvalChanged();
        }
        throw exception;
      }
    } catch (SQLException exception) {
      PublicationFailureLog.transactionFailed(PublicationFailureLog.Operation.SCHEDULE, exception);
      throw new IllegalStateException("Unable to schedule the approved manuscript", exception);
    }
  }

  /** Atomically releases an approved draft chapter on an existing public work. */
  public ChapterReleaseResult releaseChapter(
      UUID actorId, UUID workId, UUID chapterId, String ifMatch, String idempotencyKey) {
    Objects.requireNonNull(actorId, "actor id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    Objects.requireNonNull(ifMatch, "If-Match is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");

    PolicyService.RequiredAcceptance initialAcceptance = policyService.requiredAcceptance(actorId);
    if (initialAcceptance != PolicyService.RequiredAcceptance.COMPLETE) {
      return initialAcceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED
          ? ChapterReleaseResult.policyNotConfigured()
          : ChapterReleaseResult.policyRequired();
    }
    byte[] keyDigest = chapterKeyDigest(actorId, workId, chapterId, idempotencyKey);
    byte[] requestDigest = chapterRequestDigest(actorId, workId, chapterId, ifMatch);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<ChapterReleaseReceipt> prior =
            findChapterReleaseReceipt(connection, actorId, workId, keyDigest);
        if (prior.isPresent()) {
          ChapterReleaseReceipt receipt = prior.orElseThrow();
          if (!MessageDigest.isEqual(receipt.requestSha256(), requestDigest)) {
            connection.commit();
            return ChapterReleaseResult.keyReused();
          }
          connection.commit();
          return ChapterReleaseResult.replayed(receipt);
        }

        Optional<ChapterTarget> target =
            lockEligiblePublicChapter(connection, actorId, workId, chapterId);
        if (target.isEmpty()) {
          connection.commit();
          return ChapterReleaseResult.chapterNotFound();
        }

        try (PreparedStatement lock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lock.execute();
        }
        Optional<CurrentPolicy> currentPolicy = readCurrentPolicy(connection);
        if (currentPolicy.isEmpty()) {
          connection.commit();
          return ChapterReleaseResult.rightsPolicyNotConfigured();
        }
        CurrentPolicy policy = currentPolicy.orElseThrow();
        Optional<Instant> acceptedAt =
            findAcceptance(connection, actorId, workId, policy.version(), policy.sha256());
        if (acceptedAt.isEmpty()) {
          connection.commit();
          return ChapterReleaseResult.rightsPolicyNotAccepted();
        }

        ReadinessSnapshot readiness = readChapterReadinessSnapshot(connection, workId, chapterId);
        String currentEtag = readiness.etag(workId, policy, acceptedAt.orElseThrow());
        if (!currentEtag.equals(ifMatch)) {
          connection.commit();
          return ChapterReleaseResult.stale(currentEtag);
        }

        Optional<ApprovedChapterRequest> latest = latestApprovedChapterRequest(connection, workId);
        if (latest.isEmpty()) {
          connection.commit();
          return ChapterReleaseResult.approvalRequired();
        }
        ApprovedChapterRequest request = latest.orElseThrow();
        if (!chapterId.equals(request.targetChapterId())
            || !"APPROVED_UNPUBLISHED".equals(request.state())) {
          connection.commit();
          return ChapterReleaseResult.approvalRequired();
        }
        if (!policy.version().equals(request.policyVersion())
            || !MessageDigest.isEqual(policy.sha256(), request.policySha256())) {
          connection.commit();
          return ChapterReleaseResult.rightsPolicyStale();
        }
        List<String> currentScopes = currentChapterScopes(connection, workId, chapterId, request);
        if (!currentScopes.contains("PUBLIC_METADATA") || !currentScopes.contains("CHAPTER_TEXT")) {
          connection.commit();
          return ChapterReleaseResult.approvalRequired();
        }
        UUID reviewerId = latestApprovingReviewer(connection, request.id()).orElse(null);
        if (reviewerId == null || actorId.equals(reviewerId)) {
          connection.commit();
          return ChapterReleaseResult.approvalRequired();
        }

        ChapterReleaseReceipt receipt =
            insertChapterReleaseEvent(
                connection,
                actorId,
                reviewerId,
                workId,
                chapterId,
                request,
                keyDigest,
                requestDigest,
                target.orElseThrow().contentVersion(),
                target.orElseThrow().contentVersion() + 1);
        connection.commit();
        return ChapterReleaseResult.created(receipt);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        if (exception instanceof SQLException sqlException
            && "23514".equals(sqlException.getSQLState())) {
          PublicationFailureLog.guardRejected(
              PublicationFailureLog.Operation.CHAPTER_RELEASE, sqlException);
          return ChapterReleaseResult.approvalChanged();
        }
        throw exception;
      }
    } catch (SQLException exception) {
      PublicationFailureLog.transactionFailed(
          PublicationFailureLog.Operation.CHAPTER_RELEASE, exception);
      throw new IllegalStateException("Unable to publish the approved draft chapter", exception);
    }
  }

  private static Optional<ChapterTarget> lockEligiblePublicChapter(
      Connection connection, UUID actorId, UUID workId, UUID chapterId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT work.publication_content_version
                  FROM works work
                  JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
                  JOIN libraries library ON library.id = work.library_id
                    AND library.owner_user_id = work.owner_user_id
                    AND library.deleted_at IS NULL AND library.workspace_id IS NULL
                  JOIN authoring_manuscript_creations creation
                    ON creation.work_id = work.id AND creation.actor_user_id = work.owner_user_id
                  JOIN chapters chapter ON chapter.work_id = work.id AND chapter.id = ?
                    AND chapter.status = 'draft' AND chapter.deleted_at IS NULL
                  JOIN chapter_revisions revision ON revision.id = chapter.source_revision_id
                    AND revision.chapter_id = chapter.id
                    AND revision.created_by_user_id = work.owner_user_id
                 WHERE work.id = ? AND work.owner_user_id = ?
                   AND work.source_kind = 'authored' AND work.visibility = 'public'
                   AND work.status = 'active' AND work.deleted_at IS NULL
                 FOR UPDATE OF work, owner, library, creation, chapter, revision
                """)) {
      select.setObject(1, chapterId);
      select.setObject(2, workId);
      select.setObject(3, actorId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(new ChapterTarget(row.getLong("publication_content_version")))
            : Optional.empty();
      }
    }
  }

  private static Optional<LockedWork> lockEligibleWork(
      Connection connection, UUID actorId, UUID workId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT work.id, work.publication_content_version
                  FROM works work
                  JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
                  JOIN libraries library
                    ON library.id = work.library_id
                   AND library.owner_user_id = work.owner_user_id
                   AND library.deleted_at IS NULL
                   AND library.workspace_id IS NULL
                  JOIN authoring_manuscript_creations creation
                    ON creation.work_id = work.id
                   AND creation.actor_user_id = work.owner_user_id
                 WHERE work.id = ? AND work.owner_user_id = ?
                   AND work.source_kind = 'authored' AND work.visibility = 'private'
                   AND work.status = 'active' AND work.deleted_at IS NULL
                 FOR UPDATE OF work, owner, library, creation
                """)) {
      select.setObject(1, workId);
      select.setObject(2, actorId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new LockedWork(
                    row.getObject("id", UUID.class), row.getLong("publication_content_version")))
            : Optional.empty();
      }
    }
  }

  private static boolean isOwnedPublicWork(Connection connection, UUID actorId, UUID workId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT 1 FROM works WHERE id = ? AND owner_user_id = ? AND visibility = 'public'")) {
      select.setObject(1, workId);
      select.setObject(2, actorId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static Optional<UUID> latestApprovedRequestId(Connection connection, UUID workId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request.id
                  FROM publication_requests request
                  JOIN publication_request_current_state state ON state.request_id = request.id
                 WHERE request.work_id = ?
                 ORDER BY request.request_version DESC, request.id DESC
                 LIMIT 1
                 FOR UPDATE OF request
                """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getObject("id", UUID.class)) : Optional.empty();
      }
    }
  }

  private static String currentRequestState(Connection connection, UUID requestId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT current_state FROM publication_request_current_state WHERE request_id = ?")) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getString("current_state") : null;
      }
    }
  }

  private static Optional<ApprovedRequest> readApprovedRequest(
      Connection connection, UUID requestId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request.id, request.work_id, request.owner_user_id, request.policy_version,
                       request.policy_sha256, request.snapshot_sha256
                  FROM publication_requests request
                  JOIN publication_request_current_state state ON state.request_id = request.id
                 WHERE request.id = ? AND state.current_state = 'APPROVED_UNPUBLISHED'
                """)) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new ApprovedRequest(
                    row.getObject("id", UUID.class),
                    row.getObject("work_id", UUID.class),
                    row.getObject("owner_user_id", UUID.class),
                    row.getString("policy_version"),
                    row.getBytes("policy_sha256"),
                    row.getBytes("snapshot_sha256")))
            : Optional.empty();
      }
    }
  }

  private static Optional<CurrentPolicy> readCurrentPolicy(Connection connection)
      throws SQLException {
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT version, effective_at, display_text, policy_sha256
                      FROM publication_rights_policy_documents
                     WHERE status = 'APPROVED' AND effective_at <= clock_timestamp()
                     ORDER BY effective_at DESC, version DESC
                     LIMIT 1
                    """);
        ResultSet row = select.executeQuery()) {
      if (!row.next()) return Optional.empty();
      String text = row.getString("display_text");
      byte[] digest = row.getBytes("policy_sha256");
      if (!MessageDigest.isEqual(digest, sha256(text))) {
        throw new SQLException("Stored publication-rights policy digest does not match its text");
      }
      return Optional.of(
          new CurrentPolicy(
              row.getString("version"),
              row.getObject("effective_at", OffsetDateTime.class).toInstant(),
              digest));
    }
  }

  private static Optional<Instant> findAcceptance(
      Connection connection, UUID actorId, UUID workId, String policyVersion, byte[] policySha256)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT accepted_at
                  FROM publication_rights_policy_acceptances
                 WHERE user_id = ? AND work_id = ? AND policy_version = ? AND policy_sha256 = ?
                """)) {
      select.setObject(1, actorId);
      select.setObject(2, workId);
      select.setString(3, policyVersion);
      select.setBytes(4, policySha256);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(row.getObject("accepted_at", OffsetDateTime.class).toInstant())
            : Optional.empty();
      }
    }
  }

  private static ReadinessSnapshot readReadinessSnapshot(Connection connection, UUID workId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                    SELECT work.publication_content_version,
                           (SELECT COUNT(*) FROM chapters chapter
                             WHERE chapter.work_id = work.id AND chapter.status = 'ready'
                               AND chapter.deleted_at IS NULL AND chapter.source_revision_id IS NOT NULL)
                               AS ready_chapter_count,
                           publication_work_scope_snapshot(work.id, 'PUBLIC_METADATA')::text
                               AS metadata_snapshot,
                           publication_work_scope_snapshot(work.id, 'CHAPTER_TEXT')::text
                               AS chapter_text_snapshot,
                           publication_work_scope_snapshot(work.id, 'COMMERCIAL_SALE')::text
                               AS commercial_sale_snapshot,
                           publication_work_scope_snapshot(work.id, 'FREE_PREVIEW')::text
                               AS free_preview_snapshot,
                           publication_work_scope_snapshot(work.id, 'TRANSLATION_ADAPTATION')::text
                               AS translation_adaptation_snapshot,
                           (SELECT state.current_state
                              FROM publication_requests request
                              JOIN publication_request_current_state state
                                ON state.request_id = request.id
                             WHERE request.work_id = work.id
                             ORDER BY request.request_version DESC, request.id DESC
                             LIMIT 1) AS current_request_state
                      FROM works work WHERE work.id = ?
                    """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("The locked manuscript disappeared");
        return new ReadinessSnapshot(
            row.getLong("publication_content_version"),
            row.getInt("ready_chapter_count"),
            row.getString("metadata_snapshot"),
            row.getString("chapter_text_snapshot"),
            row.getString("commercial_sale_snapshot"),
            row.getString("free_preview_snapshot"),
            row.getString("translation_adaptation_snapshot"),
            row.getString("current_request_state"));
      }
    }
  }

  private static ReadinessSnapshot readChapterReadinessSnapshot(
      Connection connection, UUID workId, UUID chapterId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT work.publication_content_version,
                       (SELECT COUNT(*) FROM chapters chapter
                         WHERE chapter.work_id = work.id AND chapter.status = 'ready'
                           AND chapter.deleted_at IS NULL AND chapter.source_revision_id IS NOT NULL) + 1
                           AS ready_chapter_count,
                       publication_work_scope_snapshot_for_chapter(work.id, 'PUBLIC_METADATA', ?)::text
                           AS metadata_snapshot,
                       publication_work_scope_snapshot_for_chapter(work.id, 'CHAPTER_TEXT', ?)::text
                           AS chapter_text_snapshot,
                       publication_work_scope_snapshot_for_chapter(work.id, 'COMMERCIAL_SALE', ?)::text
                           AS commercial_sale_snapshot,
                       publication_work_scope_snapshot_for_chapter(work.id, 'FREE_PREVIEW', ?)::text
                           AS free_preview_snapshot,
                       publication_work_scope_snapshot_for_chapter(work.id, 'TRANSLATION_ADAPTATION', ?)::text
                           AS translation_adaptation_snapshot,
                       (SELECT state.current_state FROM publication_requests request
                         JOIN publication_request_current_state state ON state.request_id = request.id
                        WHERE request.work_id = work.id
                        ORDER BY request.request_version DESC, request.id DESC LIMIT 1)
                           AS current_request_state
                  FROM works work WHERE work.id = ?
                """)) {
      for (int parameter = 1; parameter <= 5; parameter++) select.setObject(parameter, chapterId);
      select.setObject(6, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("The locked public work disappeared");
        return new ReadinessSnapshot(
            row.getLong("publication_content_version"),
            row.getInt("ready_chapter_count"),
            row.getString("metadata_snapshot"),
            row.getString("chapter_text_snapshot"),
            row.getString("commercial_sale_snapshot"),
            row.getString("free_preview_snapshot"),
            row.getString("translation_adaptation_snapshot"),
            row.getString("current_request_state"));
      }
    }
  }

  private static Optional<ApprovedChapterRequest> latestApprovedChapterRequest(
      Connection connection, UUID workId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request.id, request.target_chapter_id, request.request_version,
                       request.policy_version, request.policy_sha256, request.snapshot_sha256,
                       state.current_state
                  FROM publication_requests request
                  JOIN publication_request_current_state state ON state.request_id = request.id
                 WHERE request.work_id = ?
                 ORDER BY request.request_version DESC, request.id DESC
                 LIMIT 1 FOR UPDATE OF request
                """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new ApprovedChapterRequest(
                    row.getObject("id", UUID.class),
                    row.getObject("target_chapter_id", UUID.class),
                    row.getInt("request_version"),
                    row.getString("policy_version"),
                    row.getBytes("policy_sha256"),
                    row.getBytes("snapshot_sha256"),
                    row.getString("current_state")))
            : Optional.empty();
      }
    }
  }

  private static List<String> currentChapterScopes(
      Connection connection, UUID workId, UUID chapterId, ApprovedChapterRequest request)
      throws SQLException {
    List<String> scopes = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT scope.scope_code, scope.scope_snapshot::text AS requested_snapshot,
                       scope.snapshot_sha256,
                       publication_work_scope_snapshot_for_chapter(?, scope.scope_code, ?)::text
                           AS current_snapshot
                  FROM publication_request_scope_snapshots scope
                 WHERE scope.request_id = ?
                 ORDER BY scope.scope_code FOR SHARE OF scope
                """)) {
      select.setObject(1, workId);
      select.setObject(2, chapterId);
      select.setObject(3, request.id());
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          String scopeJson = rows.getString("requested_snapshot");
          if (!MessageDigest.isEqual(rows.getBytes("snapshot_sha256"), sha256(scopeJson))
              || !Objects.equals(scopeJson, rows.getString("current_snapshot"))) {
            return List.of();
          }
          scopes.add(rows.getString("scope_code"));
        }
      }
    }
    return List.copyOf(scopes);
  }

  private static Optional<UUID> latestApprovingReviewer(Connection connection, UUID requestId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT actor_user_id FROM publication_review_events
                 WHERE request_id = ? AND action_code = 'APPROVED'
                 ORDER BY event_sequence DESC LIMIT 1
                """)) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getObject(1, UUID.class)) : Optional.empty();
      }
    }
  }

  private static Optional<ChapterReleaseReceipt> findChapterReleaseReceipt(
      Connection connection, UUID actorId, UUID workId, byte[] keyDigest) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, work_id, chapter_id, request_id, publication_content_version,
                       released_at, request_sha256
                  FROM chapter_publication_release_events
                 WHERE actor_user_id = ? AND work_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, actorId);
      select.setObject(2, workId);
      select.setBytes(3, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readChapterReleaseReceipt(row)) : Optional.empty();
      }
    }
  }

  private static ChapterReleaseReceipt insertChapterReleaseEvent(
      Connection connection,
      UUID actorId,
      UUID reviewerId,
      UUID workId,
      UUID chapterId,
      ApprovedChapterRequest request,
      byte[] keyDigest,
      byte[] requestDigest,
      long previousContentVersion,
      long publicationContentVersion)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO chapter_publication_release_events (
                    id, work_id, chapter_id, request_id, actor_user_id, reviewer_user_id,
                    policy_version, policy_sha256, snapshot_sha256, previous_content_version,
                    publication_content_version, idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, work_id, chapter_id, request_id, publication_content_version,
                          released_at, request_sha256
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, workId);
      insert.setObject(3, chapterId);
      insert.setObject(4, request.id());
      insert.setObject(5, actorId);
      insert.setObject(6, reviewerId);
      insert.setString(7, request.policyVersion());
      insert.setBytes(8, request.policySha256());
      insert.setBytes(9, request.snapshotSha256());
      insert.setLong(10, previousContentVersion);
      insert.setLong(11, publicationContentVersion);
      insert.setBytes(12, keyDigest);
      insert.setBytes(13, requestDigest);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Chapter release returned no receipt");
        return readChapterReleaseReceipt(row);
      }
    }
  }

  private static ChapterReleaseReceipt readChapterReleaseReceipt(ResultSet row)
      throws SQLException {
    return new ChapterReleaseReceipt(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getObject("request_id", UUID.class),
        row.getLong("publication_content_version"),
        row.getObject("released_at", OffsetDateTime.class).toInstant(),
        row.getBytes("request_sha256"));
  }

  private static List<String> currentApprovedScopes(Connection connection, ApprovedRequest request)
      throws SQLException {
    List<String> scopes = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT scope.scope_code,
                       scope.scope_snapshot::text AS requested_snapshot,
                       publication_work_scope_snapshot(?, scope.scope_code)::text
                           AS current_snapshot,
                       EXISTS (
                           SELECT 1
                             FROM publication_scope_access_approvals approval
                            WHERE approval.work_id = ?
                              AND approval.scope_code = scope.scope_code
                              AND approval.approved_snapshot = scope.scope_snapshot
                              AND approval.policy_version = ?
                              AND approval.policy_sha256 = ?
                              AND approval.approved_at <= clock_timestamp()
                              AND (approval.expires_at IS NULL OR approval.expires_at > clock_timestamp())
                              AND NOT EXISTS (
                                  SELECT 1 FROM publication_scope_access_revocations revocation
                                   WHERE revocation.approval_id = approval.id
                              )
                       ) AS has_current_approval
                  FROM publication_request_scope_snapshots scope
                 WHERE scope.request_id = ?
                 ORDER BY scope.scope_code
                 FOR SHARE OF scope
                """)) {
      select.setObject(1, request.workId());
      select.setObject(2, request.workId());
      select.setString(3, request.policyVersion());
      select.setBytes(4, request.policySha256());
      select.setObject(5, request.id());
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          String scope = rows.getString("scope_code");
          boolean snapshotMatches =
              Objects.equals(
                  rows.getString("requested_snapshot"), rows.getString("current_snapshot"));
          boolean hasCurrentApproval = rows.getBoolean("has_current_approval");
          if (!snapshotMatches || !hasCurrentApproval) {
            PublicationFailureLog.staleApprovalScope(snapshotMatches, hasCurrentApproval);
            return List.of();
          }
          scopes.add(scope);
        }
      }
    }
    return List.copyOf(scopes);
  }

  private static Optional<ReleaseReceipt> findReceipt(
      Connection connection, UUID actorId, UUID workId, byte[] keyDigest) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, work_id, request_id, publication_content_version, released_at,
                       request_sha256
                  FROM publication_release_events
                 WHERE actor_user_id = ? AND work_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, actorId);
      select.setObject(2, workId);
      select.setBytes(3, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readReceipt(row)) : Optional.empty();
      }
    }
  }

  private static Optional<ReleaseReceipt> findReleaseForRequest(
      Connection connection, UUID requestId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, work_id, request_id, publication_content_version, released_at,
                       request_sha256
                  FROM publication_release_events WHERE request_id = ?
                """)) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readReceipt(row)) : Optional.empty();
      }
    }
  }

  private static Optional<ScheduleReceipt> findScheduleReceipt(
      Connection connection, UUID actorId, UUID workId, byte[] keyDigest) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, work_id, request_id, scheduled_at, state, request_sha256
                  FROM publication_release_schedules
                 WHERE owner_user_id = ? AND work_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, actorId);
      select.setObject(2, workId);
      select.setBytes(3, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readScheduleReceipt(row)) : Optional.empty();
      }
    }
  }

  private static Optional<ScheduleReceipt> findActiveScheduleForRequest(
      Connection connection, UUID requestId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, work_id, request_id, scheduled_at, state, request_sha256
                  FROM publication_release_schedules
                 WHERE request_id = ? AND state IN ('SCHEDULED', 'PROCESSING')
                 FOR UPDATE
                """)) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readScheduleReceipt(row)) : Optional.empty();
      }
    }
  }

  private static ScheduleReceipt insertSchedule(
      Connection connection,
      UUID actorId,
      LockedWork work,
      ApprovedRequest request,
      String readinessEtag,
      Instant scheduledAt,
      byte[] keyDigest,
      byte[] requestDigest)
      throws SQLException {
    ScheduleReceipt receipt;
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_release_schedules (
                    id, work_id, request_id, owner_user_id, scheduled_at, readiness_etag,
                    idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, work_id, request_id, scheduled_at, state, request_sha256
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, work.id());
      insert.setObject(3, request.id());
      insert.setObject(4, actorId);
      insert.setObject(5, OffsetDateTime.ofInstant(scheduledAt, java.time.ZoneOffset.UTC));
      insert.setString(6, readinessEtag);
      insert.setBytes(7, keyDigest);
      insert.setBytes(8, requestDigest);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Publication schedule returned no receipt");
        receipt = readScheduleReceipt(row);
      }
    }
    insertScheduleEvent(connection, receipt.id(), receipt.workId(), actorId, "SCHEDULED", null);
    return receipt;
  }

  private static ScheduleReceipt readScheduleReceipt(ResultSet row) throws SQLException {
    return new ScheduleReceipt(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("request_id", UUID.class),
        row.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
        row.getString("state"),
        row.getBytes("request_sha256"));
  }

  private static void insertScheduleEvent(
      Connection connection,
      UUID scheduleId,
      UUID workId,
      UUID actorId,
      String action,
      String failureCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_release_schedule_events (
                    id, schedule_id, work_id, actor_user_id, action_code, safe_failure_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, scheduleId);
      insert.setObject(3, workId);
      insert.setObject(4, actorId);
      insert.setString(5, action);
      insert.setString(6, failureCode);
      insert.executeUpdate();
    }
  }

  private static ReleaseReceipt insertReleaseEvent(
      Connection connection,
      UUID actorId,
      LockedWork work,
      ApprovedRequest request,
      byte[] keyDigest,
      byte[] requestDigest,
      long contentVersion)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_release_events (
                    id, work_id, request_id, actor_user_id, policy_version, policy_sha256,
                    snapshot_sha256, publication_content_version, idempotency_key_sha256,
                    request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, work_id, request_id, publication_content_version, released_at,
                          request_sha256
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, work.id());
      insert.setObject(3, request.id());
      insert.setObject(4, actorId);
      insert.setString(5, request.policyVersion());
      insert.setBytes(6, request.policySha256());
      insert.setBytes(7, request.snapshotSha256());
      insert.setLong(8, contentVersion);
      insert.setBytes(9, keyDigest);
      insert.setBytes(10, requestDigest);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Publication release returned no receipt");
        return readReceipt(row);
      }
    }
  }

  private static ReleaseReceipt readReceipt(ResultSet row) throws SQLException {
    return new ReleaseReceipt(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("request_id", UUID.class),
        row.getLong("publication_content_version"),
        row.getObject("released_at", OffsetDateTime.class).toInstant(),
        row.getBytes("request_sha256"));
  }

  private static byte[] keyDigest(UUID actorId, UUID workId, String key) {
    return sha256(String.join("\n", KEY_DOMAIN, actorId.toString(), workId.toString(), key));
  }

  private static byte[] requestDigest(UUID actorId, UUID workId, String ifMatch) {
    return sha256(
        String.join(
            "\n", REQUEST_DOMAIN, actorId.toString(), workId.toString(), "PUBLISH_NOW", ifMatch));
  }

  private static byte[] chapterKeyDigest(UUID actorId, UUID workId, UUID chapterId, String key) {
    return sha256(
        String.join(
            "\n",
            "chapter-publication-release-key-v1",
            actorId.toString(),
            workId.toString(),
            chapterId.toString(),
            key));
  }

  private static byte[] scheduleKeyDigest(UUID actorId, UUID workId, String key) {
    return sha256(
        String.join(
            "\n", "publication-schedule-key-v1", actorId.toString(), workId.toString(), key));
  }

  private static byte[] scheduleRequestDigest(
      UUID actorId, UUID workId, String ifMatch, Instant scheduledAt) {
    return sha256(
        String.join(
            "\n",
            "publication-schedule-request-v1",
            actorId.toString(),
            workId.toString(),
            "SCHEDULE_AT",
            ifMatch,
            scheduledAt.toString()));
  }

  private static byte[] chapterRequestDigest(
      UUID actorId, UUID workId, UUID chapterId, String ifMatch) {
    return sha256(
        String.join(
            "\n",
            "chapter-publication-release-request-v1",
            actorId.toString(),
            workId.toString(),
            chapterId.toString(),
            ifMatch));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record LockedWork(UUID id, long contentVersion) {}

  public record ScheduleReceipt(
      UUID id,
      UUID workId,
      UUID requestId,
      Instant scheduledAt,
      String state,
      byte[] requestSha256) {
    public ScheduleReceipt {
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }
  }

  private record ChapterTarget(long contentVersion) {}

  private record ApprovedChapterRequest(
      UUID id,
      UUID targetChapterId,
      int requestVersion,
      String policyVersion,
      byte[] policySha256,
      byte[] snapshotSha256,
      String state) {
    private ApprovedChapterRequest {
      policySha256 = policySha256.clone();
      snapshotSha256 = snapshotSha256.clone();
    }

    @Override
    public byte[] policySha256() {
      return policySha256.clone();
    }

    @Override
    public byte[] snapshotSha256() {
      return snapshotSha256.clone();
    }
  }

  private record CurrentPolicy(String version, Instant effectiveAt, byte[] sha256) {
    private CurrentPolicy {
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }
  }

  private record ApprovedRequest(
      UUID id,
      UUID workId,
      UUID ownerId,
      String policyVersion,
      byte[] policySha256,
      byte[] snapshotSha256) {
    private ApprovedRequest {
      policySha256 = policySha256.clone();
      snapshotSha256 = snapshotSha256.clone();
    }

    @Override
    public byte[] policySha256() {
      return policySha256.clone();
    }

    @Override
    public byte[] snapshotSha256() {
      return snapshotSha256.clone();
    }
  }

  private record ReadinessSnapshot(
      long contentVersion,
      int readyChapterCount,
      String metadataSnapshot,
      String chapterTextSnapshot,
      String commercialSaleSnapshot,
      String freePreviewSnapshot,
      String translationAdaptationSnapshot,
      String currentRequestState) {
    private String etag(UUID workId, CurrentPolicy policy, Instant acceptedAt) {
      return PublicationSnapshotEtags.readiness(
          workId,
          contentVersion,
          readyChapterCount,
          policy.version(),
          HexFormat.of().formatHex(policy.sha256()),
          acceptedAt,
          currentRequestState,
          List.of(
              PublicationSnapshotEtags.scope("PUBLIC_METADATA", metadataSnapshot),
              PublicationSnapshotEtags.scope("CHAPTER_TEXT", chapterTextSnapshot),
              PublicationSnapshotEtags.scope("COMMERCIAL_SALE", commercialSaleSnapshot),
              PublicationSnapshotEtags.scope("FREE_PREVIEW", freePreviewSnapshot),
              PublicationSnapshotEtags.scope(
                  "TRANSLATION_ADAPTATION", translationAdaptationSnapshot)));
    }
  }

  public record ReleaseReceipt(
      UUID eventId,
      UUID workId,
      UUID requestId,
      long publicationContentVersion,
      Instant publishedAt,
      byte[] requestSha256) {
    public ReleaseReceipt {
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }
  }

  public record ChapterReleaseReceipt(
      UUID eventId,
      UUID workId,
      UUID chapterId,
      UUID requestId,
      long publicationContentVersion,
      Instant publishedAt,
      byte[] requestSha256) {
    public ChapterReleaseReceipt {
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }
  }

  public record ChapterReleaseResult(
      ChapterReleaseOutcome outcome, ChapterReleaseReceipt receipt, String currentEtag) {
    static ChapterReleaseResult created(ChapterReleaseReceipt receipt) {
      return new ChapterReleaseResult(ChapterReleaseOutcome.CREATED, receipt, null);
    }

    static ChapterReleaseResult replayed(ChapterReleaseReceipt receipt) {
      return new ChapterReleaseResult(ChapterReleaseOutcome.REPLAYED, receipt, null);
    }

    static ChapterReleaseResult keyReused() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.KEY_REUSED, null, null);
    }

    static ChapterReleaseResult chapterNotFound() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.CHAPTER_NOT_FOUND, null, null);
    }

    static ChapterReleaseResult approvalRequired() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.APPROVAL_REQUIRED, null, null);
    }

    static ChapterReleaseResult policyRequired() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.POLICY_REQUIRED, null, null);
    }

    static ChapterReleaseResult policyNotConfigured() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.POLICY_NOT_CONFIGURED, null, null);
    }

    static ChapterReleaseResult rightsPolicyNotConfigured() {
      return new ChapterReleaseResult(
          ChapterReleaseOutcome.RIGHTS_POLICY_NOT_CONFIGURED, null, null);
    }

    static ChapterReleaseResult rightsPolicyNotAccepted() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.RIGHTS_POLICY_NOT_ACCEPTED, null, null);
    }

    static ChapterReleaseResult rightsPolicyStale() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.RIGHTS_POLICY_STALE, null, null);
    }

    static ChapterReleaseResult approvalChanged() {
      return new ChapterReleaseResult(ChapterReleaseOutcome.APPROVAL_CHANGED, null, null);
    }

    static ChapterReleaseResult stale(String currentEtag) {
      return new ChapterReleaseResult(ChapterReleaseOutcome.STALE, null, currentEtag);
    }
  }

  public enum ChapterReleaseOutcome {
    CREATED,
    REPLAYED,
    KEY_REUSED,
    CHAPTER_NOT_FOUND,
    APPROVAL_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_NOT_ACCEPTED,
    RIGHTS_POLICY_STALE,
    APPROVAL_CHANGED,
    STALE
  }

  public record ReleaseResult(Outcome outcome, ReleaseReceipt receipt, String currentEtag) {
    static ReleaseResult created(ReleaseReceipt receipt) {
      return new ReleaseResult(Outcome.CREATED, receipt, null);
    }

    static ReleaseResult replayed(ReleaseReceipt receipt) {
      return new ReleaseResult(Outcome.REPLAYED, receipt, null);
    }

    static ReleaseResult keyReused() {
      return new ReleaseResult(Outcome.KEY_REUSED, null, null);
    }

    static ReleaseResult alreadyPublished() {
      return new ReleaseResult(Outcome.ALREADY_PUBLISHED, null, null);
    }

    static ReleaseResult workNotFound() {
      return new ReleaseResult(Outcome.WORK_NOT_FOUND, null, null);
    }

    static ReleaseResult approvalRequired() {
      return new ReleaseResult(Outcome.APPROVAL_REQUIRED, null, null);
    }

    static ReleaseResult policyRequired() {
      return new ReleaseResult(Outcome.POLICY_REQUIRED, null, null);
    }

    static ReleaseResult policyNotConfigured() {
      return new ReleaseResult(Outcome.POLICY_NOT_CONFIGURED, null, null);
    }

    static ReleaseResult rightsPolicyNotConfigured() {
      return new ReleaseResult(Outcome.RIGHTS_POLICY_NOT_CONFIGURED, null, null);
    }

    static ReleaseResult rightsPolicyNotAccepted() {
      return new ReleaseResult(Outcome.RIGHTS_POLICY_NOT_ACCEPTED, null, null);
    }

    static ReleaseResult rightsPolicyStale() {
      return new ReleaseResult(Outcome.RIGHTS_POLICY_STALE, null, null);
    }

    static ReleaseResult approvalChanged() {
      return new ReleaseResult(Outcome.APPROVAL_CHANGED, null, null);
    }

    static ReleaseResult stale(String currentEtag) {
      return new ReleaseResult(Outcome.STALE, null, currentEtag);
    }
  }

  public record ScheduleResult(
      ScheduleOutcome outcome, ScheduleReceipt receipt, String currentEtag) {
    static ScheduleResult created(ScheduleReceipt receipt) {
      return new ScheduleResult(ScheduleOutcome.CREATED, receipt, null);
    }

    static ScheduleResult replayed(ScheduleReceipt receipt) {
      return new ScheduleResult(ScheduleOutcome.REPLAYED, receipt, null);
    }

    static ScheduleResult keyReused() {
      return new ScheduleResult(ScheduleOutcome.KEY_REUSED, null, null);
    }

    static ScheduleResult timeInvalid() {
      return new ScheduleResult(ScheduleOutcome.TIME_INVALID, null, null);
    }

    static ScheduleResult alreadyScheduled() {
      return new ScheduleResult(ScheduleOutcome.ALREADY_SCHEDULED, null, null);
    }

    static ScheduleResult alreadyReleased() {
      return new ScheduleResult(ScheduleOutcome.ALREADY_RELEASED, null, null);
    }

    static ScheduleResult workNotFound() {
      return new ScheduleResult(ScheduleOutcome.WORK_NOT_FOUND, null, null);
    }

    static ScheduleResult approvalRequired() {
      return new ScheduleResult(ScheduleOutcome.APPROVAL_REQUIRED, null, null);
    }

    static ScheduleResult policyRequired() {
      return new ScheduleResult(ScheduleOutcome.POLICY_REQUIRED, null, null);
    }

    static ScheduleResult policyNotConfigured() {
      return new ScheduleResult(ScheduleOutcome.POLICY_NOT_CONFIGURED, null, null);
    }

    static ScheduleResult rightsPolicyNotConfigured() {
      return new ScheduleResult(ScheduleOutcome.RIGHTS_POLICY_NOT_CONFIGURED, null, null);
    }

    static ScheduleResult rightsPolicyNotAccepted() {
      return new ScheduleResult(ScheduleOutcome.RIGHTS_POLICY_NOT_ACCEPTED, null, null);
    }

    static ScheduleResult rightsPolicyStale() {
      return new ScheduleResult(ScheduleOutcome.RIGHTS_POLICY_STALE, null, null);
    }

    static ScheduleResult approvalChanged() {
      return new ScheduleResult(ScheduleOutcome.APPROVAL_CHANGED, null, null);
    }

    static ScheduleResult stale(String currentEtag) {
      return new ScheduleResult(ScheduleOutcome.STALE, null, currentEtag);
    }
  }

  public enum ScheduleOutcome {
    CREATED,
    REPLAYED,
    KEY_REUSED,
    TIME_INVALID,
    ALREADY_SCHEDULED,
    ALREADY_RELEASED,
    WORK_NOT_FOUND,
    APPROVAL_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_NOT_ACCEPTED,
    RIGHTS_POLICY_STALE,
    APPROVAL_CHANGED,
    STALE
  }

  public enum Outcome {
    CREATED,
    REPLAYED,
    KEY_REUSED,
    ALREADY_PUBLISHED,
    WORK_NOT_FOUND,
    APPROVAL_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_NOT_ACCEPTED,
    RIGHTS_POLICY_STALE,
    APPROVAL_CHANGED,
    STALE
  }
}
