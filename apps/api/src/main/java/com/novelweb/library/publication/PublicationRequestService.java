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
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Creates immutable owner publication requests and their first audit event atomically. */
@Service
@Profile("postgres")
public class PublicationRequestService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PublicationRequestService.class);
  private static final String KEY_DOMAIN = "publication-request-idempotency-v1";
  private static final String REQUEST_DOMAIN = "publication-request-body-v1";
  private static final String WITHDRAWAL_KEY_DOMAIN = "publication-request-withdrawal-key-v1";
  private static final String WITHDRAWAL_REQUEST_DOMAIN = "publication-request-withdrawal-v1";
  private static final Set<String> SUPERSEDEABLE_STATES =
      Set.of("NEEDS_INFORMATION", "REJECTED", "APPROVED_UNPUBLISHED");

  private final DataSource dataSource;
  private final PolicyService policyService;
  private final PublicationRequestRateLimiter rateLimiter;

  public PublicationRequestService(
      DataSource dataSource,
      PolicyService policyService,
      PublicationRequestRateLimiter rateLimiter) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rate limiter is required");
  }

  public SubmitResult submit(
      UUID userId,
      UUID workId,
      String expectedSnapshotEtag,
      String idempotencyKey,
      Submission submission) {
    return submit(userId, workId, null, expectedSnapshotEtag, idempotencyKey, submission);
  }

  /** Submits one current draft chapter from an already public owner-authored work. */
  public SubmitResult submitChapter(
      UUID userId,
      UUID workId,
      UUID chapterId,
      String expectedSnapshotEtag,
      String idempotencyKey,
      Submission submission) {
    Objects.requireNonNull(chapterId, "chapter id is required");
    return submit(userId, workId, chapterId, expectedSnapshotEtag, idempotencyKey, submission);
  }

  private SubmitResult submit(
      UUID userId,
      UUID workId,
      UUID targetChapterId,
      String expectedSnapshotEtag,
      String idempotencyKey,
      Submission submission) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(expectedSnapshotEtag, "snapshot ETag is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");
    Objects.requireNonNull(submission, "submission is required");

    List<ScopeClaim> claims = submission.claims().stream().sorted().toList();
    byte[] idempotencyDigest = idempotencyDigest(userId, workId, idempotencyKey);
    byte[] requestDigest =
        requestDigest(userId, workId, targetChapterId, expectedSnapshotEtag, submission, claims);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockEligibleWork(connection, userId, workId, targetChapterId)) {
          connection.commit();
          return SubmitResult.workNotFound();
        }

        PolicyService.RequiredAcceptance acceptanceState = policyService.requiredAcceptance(userId);
        if (acceptanceState == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return SubmitResult.policyRequired();
        }
        if (acceptanceState == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return SubmitResult.policyNotConfigured();
        }

        Optional<RequestRow> prior =
            findIdempotentRequest(connection, userId, workId, idempotencyDigest);
        if (prior.isPresent()) {
          RequestRow row = prior.orElseThrow();
          if (!MessageDigest.isEqual(row.requestDigest(), requestDigest)) {
            connection.commit();
            return SubmitResult.keyReused();
          }
          List<String> scopeCodes = findRequestScopeCodes(connection, row.id());
          connection.commit();
          return SubmitResult.replayed(receipt(row, scopeCodes));
        }

        try (PreparedStatement lock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lock.execute();
        }
        Optional<PolicyDocument> activePolicy = currentPolicy(connection);
        if (activePolicy.isEmpty()) {
          connection.commit();
          return SubmitResult.rightsPolicyNotConfigured();
        }
        PolicyDocument policy = activePolicy.orElseThrow();
        if (!policy.version().equals(submission.policyVersion())) {
          connection.commit();
          return SubmitResult.stalePolicy(policy.version());
        }
        Optional<Acceptance> acceptance =
            findAcceptance(connection, userId, workId, policy.version());
        if (acceptance.isEmpty()
            || !MessageDigest.isEqual(acceptance.orElseThrow().policyDigest(), policy.sha256())) {
          connection.commit();
          return SubmitResult.rightsPolicyAcceptanceRequired();
        }

        WorkSnapshot workSnapshot = readWorkSnapshot(connection, workId, targetChapterId);
        Optional<RequestHistory> previous = latestRequest(connection, workId, targetChapterId);
        String currentRequestState = previous.map(RequestHistory::state).orElse(null);
        if ("PENDING_REVIEW".equals(currentRequestState)) {
          connection.commit();
          return SubmitResult.requestAlreadyPending();
        }
        if (workSnapshot.readyChapterCount() < 1) {
          connection.commit();
          return SubmitResult.readyChapterRequired();
        }

        String policyDigest = HexFormat.of().formatHex(policy.sha256());
        String metadataEtag =
            PublicationSnapshotEtags.scope("PUBLIC_METADATA", workSnapshot.metadataSnapshot());
        String chapterTextEtag =
            PublicationSnapshotEtags.scope("CHAPTER_TEXT", workSnapshot.chapterTextSnapshot());
        String commercialSaleEtag =
            PublicationSnapshotEtags.scope(
                "COMMERCIAL_SALE", workSnapshot.commercialSaleSnapshot());
        String translationAdaptationEtag =
            PublicationSnapshotEtags.scope(
                "TRANSLATION_ADAPTATION", workSnapshot.translationAdaptationSnapshot());
        String freePreviewEtag =
            PublicationSnapshotEtags.scope("FREE_PREVIEW", workSnapshot.freePreviewSnapshot());
        String currentEtag =
            PublicationSnapshotEtags.readiness(
                workId,
                workSnapshot.contentVersion(),
                workSnapshot.readyChapterCount(),
                policy.version(),
                policyDigest,
                acceptance.orElseThrow().acceptedAt(),
                currentRequestState,
                List.of(
                    metadataEtag,
                    chapterTextEtag,
                    commercialSaleEtag,
                    freePreviewEtag,
                    translationAdaptationEtag));
        if (!currentEtag.equals(expectedSnapshotEtag)) {
          connection.commit();
          return SubmitResult.staleSnapshot(currentEtag);
        }

        List<ScopeSnapshot> selectedSnapshots =
            selectedSnapshots(
                claims,
                workSnapshot,
                metadataEtag,
                chapterTextEtag,
                commercialSaleEtag,
                translationAdaptationEtag,
                freePreviewEtag);
        byte[] snapshotDigest = snapshotDigest(selectedSnapshots);
        PublicationRequestRateLimiter.Decision rateDecision =
            rateLimiter.acquire(connection, userId);
        if (!rateDecision.allowed()) {
          connection.commit();
          return SubmitResult.rateLimited(rateDecision.retryAfterSeconds());
        }

        int requestVersion = latestRequestVersion(connection, workId) + 1;
        UUID supersedesRequestId = previous.map(RequestHistory::id).orElse(null);
        if (previous.isPresent() && SUPERSEDEABLE_STATES.contains(currentRequestState)) {
          appendSupersededEvent(
              connection, userId, previous.orElseThrow(), idempotencyDigest, requestDigest);
        }

        RequestRow created =
            insertRequest(
                connection,
                userId,
                workId,
                requestVersion,
                supersedesRequestId,
                targetChapterId,
                policy,
                snapshotDigest,
                idempotencyDigest,
                requestDigest);
        insertScopeSnapshots(connection, created.id(), selectedSnapshots);
        insertScopeClaims(connection, created.id(), claims);
        insertSubmittedEvent(
            connection, created.id(), userId, idempotencyDigest, requestDigest, snapshotDigest);
        try (var constraints = connection.createStatement()) {
          constraints.execute("SET CONSTRAINTS trg_publication_request_complete IMMEDIATE");
        }
        connection.commit();
        return SubmitResult.created(
            receipt(created, claims.stream().map(ScopeClaim::scopeCode).toList()));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      LOGGER.error(
          "Publication request transaction failed; workId={}, sqlState={}",
          workId,
          exception.getSQLState(),
          exception);
      throw new IllegalStateException("Unable to submit publication request", exception);
    }
  }

  /** Appends an owner withdrawal event for the current pending request. */
  public WithdrawalResult withdraw(
      UUID userId, UUID workId, String expectedSnapshotEtag, String idempotencyKey) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(expectedSnapshotEtag, "snapshot ETag is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");

    byte[] keyDigest = withdrawalIdempotencyDigest(userId, workId, idempotencyKey);
    byte[] requestDigest = withdrawalRequestDigest(userId, workId, expectedSnapshotEtag);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockEligibleWork(connection, userId, workId, null)) {
          connection.commit();
          return WithdrawalResult.workNotFound();
        }

        Optional<WithdrawalReceiptRow> prior =
            findIdempotentWithdrawal(connection, userId, workId, keyDigest);
        if (prior.isPresent()) {
          WithdrawalReceiptRow row = prior.orElseThrow();
          if (!MessageDigest.isEqual(row.requestDigest(), requestDigest)) {
            connection.commit();
            return WithdrawalResult.keyReused();
          }
          connection.commit();
          return WithdrawalResult.replayed(row.receipt());
        }

        // Withdrawal stays available if the publication-rights policy expires or is removed.
        // The current policy still participates in the readiness validator when one exists.
        try (PreparedStatement lock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lock.execute();
        }
        Optional<PolicyDocument> activePolicy = currentPolicy(connection);
        Acceptance acceptance = null;
        if (activePolicy.isPresent()) {
          Optional<Acceptance> storedAcceptance =
              findAcceptance(connection, userId, workId, activePolicy.orElseThrow().version());
          if (storedAcceptance.isPresent()
              && MessageDigest.isEqual(
                  storedAcceptance.orElseThrow().policyDigest(),
                  activePolicy.orElseThrow().sha256())) {
            acceptance = storedAcceptance.orElseThrow();
          }
        }

        WorkSnapshot workSnapshot = readWorkSnapshot(connection, workId, null);
        Optional<RequestHistory> latest = latestRequest(connection, workId, null);
        String currentState = latest.map(RequestHistory::state).orElse(null);
        String metadataEtag =
            PublicationSnapshotEtags.scope("PUBLIC_METADATA", workSnapshot.metadataSnapshot());
        String chapterTextEtag =
            PublicationSnapshotEtags.scope("CHAPTER_TEXT", workSnapshot.chapterTextSnapshot());
        String commercialSaleEtag =
            PublicationSnapshotEtags.scope(
                "COMMERCIAL_SALE", workSnapshot.commercialSaleSnapshot());
        String translationAdaptationEtag =
            PublicationSnapshotEtags.scope(
                "TRANSLATION_ADAPTATION", workSnapshot.translationAdaptationSnapshot());
        String freePreviewEtag =
            PublicationSnapshotEtags.scope("FREE_PREVIEW", workSnapshot.freePreviewSnapshot());
        String currentEtag =
            PublicationSnapshotEtags.readiness(
                workId,
                workSnapshot.contentVersion(),
                workSnapshot.readyChapterCount(),
                activePolicy.map(PolicyDocument::version).orElse(null),
                activePolicy
                    .map(policy -> HexFormat.of().formatHex(policy.sha256()))
                    .orElse("none"),
                acceptance == null ? null : acceptance.acceptedAt(),
                currentState,
                List.of(
                    metadataEtag,
                    chapterTextEtag,
                    commercialSaleEtag,
                    freePreviewEtag,
                    translationAdaptationEtag));
        if (!currentEtag.equals(expectedSnapshotEtag)) {
          connection.commit();
          return WithdrawalResult.staleSnapshot(currentEtag);
        }
        if (latest.isEmpty() || !"PENDING_REVIEW".equals(currentState)) {
          connection.commit();
          return WithdrawalResult.notWithdrawable(currentState);
        }

        WithdrawalReceipt receipt =
            appendWithdrawalEvent(
                connection, userId, latest.orElseThrow(), keyDigest, requestDigest);
        connection.commit();
        return WithdrawalResult.created(receipt);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to withdraw publication request", exception);
    }
  }

  private static boolean lockEligibleWork(
      Connection connection, UUID userId, UUID workId, UUID targetChapterId) throws SQLException {
    String visibilityPredicate =
        targetChapterId == null
            ? "w.visibility = 'private'"
            : "w.visibility = 'public' AND EXISTS ("
                + "SELECT 1 FROM chapters chapter JOIN chapter_revisions revision "
                + "ON revision.id = chapter.source_revision_id AND revision.chapter_id = chapter.id "
                + "WHERE chapter.id = ? AND chapter.work_id = w.id AND chapter.status = 'draft' "
                + "AND chapter.deleted_at IS NULL AND revision.created_by_user_id = w.owner_user_id)";
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1
                FROM users u
                JOIN works w ON w.owner_user_id = u.id
                JOIN libraries l ON l.id = w.library_id AND l.owner_user_id = w.owner_user_id
                JOIN authoring_manuscript_creations creation
                  ON creation.work_id = w.id AND creation.actor_user_id = w.owner_user_id
                WHERE u.id = ? AND u.status = 'active'
                  AND w.id = ? AND w.source_kind = 'authored' AND %s
                  AND w.status = 'active' AND w.deleted_at IS NULL
                  AND l.deleted_at IS NULL AND l.workspace_id IS NULL
                FOR UPDATE OF u, w, l, creation
                """
                .formatted(visibilityPredicate))) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      if (targetChapterId != null) select.setObject(3, targetChapterId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static Optional<RequestRow> findIdempotentRequest(
      Connection connection, UUID userId, UUID workId, byte[] idempotencyDigest)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, request_version, policy_version, snapshot_sha256, request_sha256, submitted_at
                  FROM publication_requests
                 WHERE owner_user_id = ? AND work_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      select.setBytes(3, idempotencyDigest);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(readRequestRow(row));
      }
    }
  }

  private static Optional<WithdrawalReceiptRow> findIdempotentWithdrawal(
      Connection connection, UUID userId, UUID workId, byte[] keyDigest) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event.id AS event_id, request.id AS request_id,
                       request.request_version, event.to_state, event.created_at,
                       event.request_sha256
                  FROM publication_review_events event
                  JOIN publication_requests request ON request.id = event.request_id
                 WHERE request.owner_user_id = ? AND request.work_id = ?
                   AND event.actor_user_id = ? AND event.action_code = 'WITHDRAWN'
                   AND event.idempotency_key_sha256 = ?
                 ORDER BY request.request_version DESC, event.event_sequence DESC
                 LIMIT 1
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      select.setObject(3, userId);
      select.setBytes(4, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new WithdrawalReceiptRow(
                row.getBytes("request_sha256"),
                new WithdrawalReceipt(
                    row.getObject("event_id", UUID.class),
                    row.getObject("request_id", UUID.class),
                    row.getInt("request_version"),
                    row.getString("to_state"),
                    row.getObject("created_at", OffsetDateTime.class).toInstant())));
      }
    }
  }

  private static List<String> findRequestScopeCodes(Connection connection, UUID requestId)
      throws SQLException {
    List<String> scopeCodes = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT scope_code FROM publication_request_scope_snapshots WHERE request_id = ?"
                + " ORDER BY CASE scope_code WHEN 'PUBLIC_METADATA' THEN 0"
                + " WHEN 'CHAPTER_TEXT' THEN 1 WHEN 'COMMERCIAL_SALE' THEN 2"
                + " WHEN 'FREE_PREVIEW' THEN 3 WHEN 'TRANSLATION_ADAPTATION' THEN 4 ELSE 5 END")) {
      select.setObject(1, requestId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) scopeCodes.add(rows.getString(1));
      }
    }
    return List.copyOf(scopeCodes);
  }

  private static Optional<PolicyDocument> currentPolicy(Connection connection) throws SQLException {
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
      byte[] digest = row.getBytes("policy_sha256");
      String text = row.getString("display_text");
      if (!MessageDigest.isEqual(digest, sha256(text))) {
        throw new SQLException("Stored publication-rights policy digest does not match its text");
      }
      return Optional.of(
          new PolicyDocument(
              row.getString("version"),
              row.getObject("effective_at", OffsetDateTime.class).toInstant(),
              digest));
    }
  }

  private static Optional<Acceptance> findAcceptance(
      Connection connection, UUID userId, UUID workId, String policyVersion) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT policy_sha256, accepted_at
                  FROM publication_rights_policy_acceptances
                 WHERE user_id = ? AND work_id = ? AND policy_version = ?
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      select.setString(3, policyVersion);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new Acceptance(
                row.getBytes("policy_sha256"),
                row.getObject("accepted_at", OffsetDateTime.class).toInstant()));
      }
    }
  }

  private static WorkSnapshot readWorkSnapshot(
      Connection connection, UUID workId, UUID targetChapterId) throws SQLException {
    String scopeSnapshotFunction =
        targetChapterId == null
            ? "publication_work_scope_snapshot(w.id, '%s')"
            : "publication_work_scope_snapshot_for_chapter(w.id, '%s', ?)";
    String query =
        """
            SELECT w.publication_content_version,
                   (SELECT COUNT(*) FROM chapters chapter WHERE chapter.work_id = w.id
                     AND chapter.status = 'ready' AND chapter.deleted_at IS NULL
                     AND chapter.source_revision_id IS NOT NULL) + %s AS ready_chapter_count,
                   %s::text AS metadata_snapshot,
                   %s::text AS chapter_text_snapshot,
                   %s::text AS commercial_sale_snapshot,
                   %s::text AS translation_adaptation_snapshot,
                   %s::text AS free_preview_snapshot
              FROM works w WHERE w.id = ?
            """
            .formatted(
                targetChapterId == null ? "0" : "1",
                scopeSnapshotFunction.formatted("PUBLIC_METADATA"),
                scopeSnapshotFunction.formatted("CHAPTER_TEXT"),
                scopeSnapshotFunction.formatted("COMMERCIAL_SALE"),
                scopeSnapshotFunction.formatted("TRANSLATION_ADAPTATION"),
                scopeSnapshotFunction.formatted("FREE_PREVIEW"));
    try (PreparedStatement select = connection.prepareStatement(query)) {
      int parameter = 1;
      if (targetChapterId != null) {
        for (int i = 0; i < 5; i++) select.setObject(parameter++, targetChapterId);
      }
      select.setObject(parameter, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next())
          throw new SQLException("Eligible manuscript disappeared during submission");
        return new WorkSnapshot(
            row.getLong("publication_content_version"),
            row.getInt("ready_chapter_count"),
            row.getString("metadata_snapshot"),
            row.getString("chapter_text_snapshot"),
            row.getString("commercial_sale_snapshot"),
            row.getString("translation_adaptation_snapshot"),
            row.getString("free_preview_snapshot"));
      }
    }
  }

  private static Optional<RequestHistory> latestRequest(
      Connection connection, UUID workId, UUID targetChapterId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request.id, request.request_version, request.snapshot_sha256,
                       state.current_state, state.event_sequence
                  FROM publication_requests request
                  JOIN publication_request_current_state state ON state.request_id = request.id
                 WHERE request.work_id = ?
                   AND request.target_chapter_id IS NOT DISTINCT FROM ?::uuid
                 ORDER BY request.request_version DESC, request.id DESC
                 LIMIT 1
                 FOR UPDATE OF request
                """)) {
      select.setObject(1, workId);
      select.setObject(2, targetChapterId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new RequestHistory(
                row.getObject("id", UUID.class),
                row.getInt("request_version"),
                row.getBytes("snapshot_sha256"),
                row.getString("current_state"),
                row.getInt("event_sequence")));
      }
    }
  }

  private static int latestRequestVersion(Connection connection, UUID workId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(request_version), 0) FROM publication_requests WHERE work_id = ?")) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Could not read the latest request version");
        return row.getInt(1);
      }
    }
  }

  private static List<ScopeSnapshot> selectedSnapshots(
      List<ScopeClaim> claims,
      WorkSnapshot workSnapshot,
      String metadataEtag,
      String chapterTextEtag,
      String commercialSaleEtag,
      String translationAdaptationEtag,
      String freePreviewEtag) {
    List<ScopeSnapshot> snapshots = new ArrayList<>();
    for (ScopeClaim claim : claims) {
      String json =
          switch (claim.scopeCode()) {
            case "PUBLIC_METADATA" -> workSnapshot.metadataSnapshot();
            case "CHAPTER_TEXT" -> workSnapshot.chapterTextSnapshot();
            case "COMMERCIAL_SALE" -> workSnapshot.commercialSaleSnapshot();
            case "TRANSLATION_ADAPTATION" -> workSnapshot.translationAdaptationSnapshot();
            case "FREE_PREVIEW" -> workSnapshot.freePreviewSnapshot();
            default -> throw new IllegalArgumentException("Unsupported publication scope");
          };
      String etag =
          switch (claim.scopeCode()) {
            case "PUBLIC_METADATA" -> metadataEtag;
            case "CHAPTER_TEXT" -> chapterTextEtag;
            case "COMMERCIAL_SALE" -> commercialSaleEtag;
            case "TRANSLATION_ADAPTATION" -> translationAdaptationEtag;
            case "FREE_PREVIEW" -> freePreviewEtag;
            default -> throw new IllegalArgumentException("Unsupported publication scope");
          };
      snapshots.add(new ScopeSnapshot(claim.scopeCode(), json, sha256(json), etag));
    }
    return List.copyOf(snapshots);
  }

  private static byte[] snapshotDigest(List<ScopeSnapshot> snapshots) {
    String canonical =
        String.join(
            "\n",
            snapshots.stream()
                .sorted()
                .map(scope -> scope.scopeCode() + "\n" + scope.snapshotJson())
                .toList());
    return sha256(canonical);
  }

  private static int scopeOrder(String scopeCode) {
    return switch (scopeCode) {
      case "PUBLIC_METADATA" -> 0;
      case "CHAPTER_TEXT" -> 1;
      case "COMMERCIAL_SALE" -> 2;
      case "FREE_PREVIEW" -> 3;
      case "TRANSLATION_ADAPTATION" -> 4;
      default -> 5;
    };
  }

  private static void appendSupersededEvent(
      Connection connection,
      UUID ownerId,
      RequestHistory previous,
      byte[] idempotencyDigest,
      byte[] requestDigest)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_review_events (
                    id, request_id, event_sequence, actor_user_id, actor_role_code,
                    action_code, from_state, to_state, idempotency_key_sha256,
                    request_sha256, snapshot_sha256)
                VALUES (?, ?, ?, ?, 'author', 'SUPERSEDED', ?, 'SUPERSEDED', ?, ?, ?)
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, previous.id());
      insert.setInt(3, previous.eventSequence() + 1);
      insert.setObject(4, ownerId);
      insert.setString(5, previous.state());
      insert.setBytes(6, idempotencyDigest);
      insert.setBytes(7, requestDigest);
      insert.setBytes(8, previous.snapshotDigest());
      if (insert.executeUpdate() != 1) throw new SQLException("Could not supersede prior request");
    }
  }

  private static RequestRow insertRequest(
      Connection connection,
      UUID ownerId,
      UUID workId,
      int requestVersion,
      UUID supersedesRequestId,
      UUID targetChapterId,
      PolicyDocument policy,
      byte[] snapshotDigest,
      byte[] idempotencyDigest,
      byte[] requestDigest)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_requests (
                    id, work_id, owner_user_id, request_version, supersedes_request_id,
                    target_chapter_id, target_revision_id,
                    policy_version, policy_sha256, snapshot_sha256,
                    idempotency_key_sha256, request_sha256)
                VALUES (?, ?, ?, ?, ?, ?,
                        CASE WHEN ?::UUID IS NULL THEN NULL ELSE (
                            SELECT source_revision_id FROM chapters WHERE id = ? AND work_id = ?
                        ) END,
                        ?, ?, ?, ?, ?)
                RETURNING id, request_version, policy_version, snapshot_sha256,
                          request_sha256, submitted_at
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, workId);
      insert.setObject(3, ownerId);
      insert.setInt(4, requestVersion);
      insert.setObject(5, supersedesRequestId);
      insert.setObject(6, targetChapterId);
      insert.setObject(7, targetChapterId);
      insert.setObject(8, targetChapterId);
      insert.setObject(9, workId);
      insert.setString(10, policy.version());
      insert.setBytes(11, policy.sha256());
      insert.setBytes(12, snapshotDigest);
      insert.setBytes(13, idempotencyDigest);
      insert.setBytes(14, requestDigest);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Publication request insert returned no receipt");
        return readRequestRow(row);
      }
    }
  }

  private static void insertScopeSnapshots(
      Connection connection, UUID requestId, List<ScopeSnapshot> snapshots) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_request_scope_snapshots
                    (request_id, scope_code, scope_snapshot, snapshot_sha256)
                VALUES (?, ?, CAST(? AS JSONB), ?)
                """)) {
      for (ScopeSnapshot snapshot : snapshots) {
        insert.setObject(1, requestId);
        insert.setString(2, snapshot.scopeCode());
        insert.setString(3, snapshot.snapshotJson());
        insert.setBytes(4, snapshot.sha256());
        insert.addBatch();
      }
      for (int inserted : insert.executeBatch()) {
        if (inserted != 1 && inserted != PreparedStatement.SUCCESS_NO_INFO) {
          throw new SQLException("Publication scope snapshot was not inserted");
        }
      }
    }
  }

  private static void insertScopeClaims(
      Connection connection, UUID requestId, List<ScopeClaim> claims) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_request_scope_claims
                    (request_id, scope_code, rights_basis_code)
                VALUES (?, ?, ?)
                """)) {
      for (ScopeClaim claim : claims) {
        insert.setObject(1, requestId);
        insert.setString(2, claim.scopeCode());
        insert.setString(3, claim.rightsBasisCode());
        insert.addBatch();
      }
      for (int inserted : insert.executeBatch()) {
        if (inserted != 1 && inserted != PreparedStatement.SUCCESS_NO_INFO) {
          throw new SQLException("Publication rights declaration was not inserted");
        }
      }
    }
  }

  private static void insertSubmittedEvent(
      Connection connection,
      UUID requestId,
      UUID ownerId,
      byte[] idempotencyDigest,
      byte[] requestDigest,
      byte[] snapshotDigest)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_review_events (
                    id, request_id, event_sequence, actor_user_id, actor_role_code,
                    action_code, from_state, to_state, idempotency_key_sha256,
                    request_sha256, snapshot_sha256)
                VALUES (?, ?, 1, ?, 'author', 'SUBMITTED', NULL, 'PENDING_REVIEW', ?, ?, ?)
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, requestId);
      insert.setObject(3, ownerId);
      insert.setBytes(4, idempotencyDigest);
      insert.setBytes(5, requestDigest);
      insert.setBytes(6, snapshotDigest);
      if (insert.executeUpdate() != 1) throw new SQLException("Submission event was not created");
    }
  }

  private static WithdrawalReceipt appendWithdrawalEvent(
      Connection connection,
      UUID ownerId,
      RequestHistory request,
      byte[] keyDigest,
      byte[] requestDigest)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_review_events (
                    id, request_id, event_sequence, actor_user_id, actor_role_code,
                    action_code, from_state, to_state, idempotency_key_sha256,
                    request_sha256, snapshot_sha256)
                VALUES (?, ?, ?, ?, 'author', 'WITHDRAWN', ?, 'WITHDRAWN', ?, ?, ?)
                RETURNING id, request_id, to_state, created_at
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, request.id());
      insert.setInt(3, request.eventSequence() + 1);
      insert.setObject(4, ownerId);
      insert.setString(5, request.state());
      insert.setBytes(6, keyDigest);
      insert.setBytes(7, requestDigest);
      insert.setBytes(8, request.snapshotDigest());
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Withdrawal event insert returned no receipt");
        return new WithdrawalReceipt(
            row.getObject("id", UUID.class),
            row.getObject("request_id", UUID.class),
            request.requestVersion(),
            row.getString("to_state"),
            row.getObject("created_at", OffsetDateTime.class).toInstant());
      }
    }
  }

  private static RequestRow readRequestRow(ResultSet row) throws SQLException {
    return new RequestRow(
        row.getObject("id", UUID.class),
        row.getInt("request_version"),
        row.getString("policy_version"),
        row.getBytes("snapshot_sha256"),
        row.getBytes("request_sha256"),
        row.getObject("submitted_at", OffsetDateTime.class).toInstant());
  }

  private static PublicationRequestReceipt receipt(RequestRow row, List<String> scopeCodes) {
    return new PublicationRequestReceipt(
        row.id(),
        row.requestVersion(),
        row.policyVersion(),
        HexFormat.of().formatHex(row.snapshotDigest()),
        List.copyOf(scopeCodes),
        row.submittedAt(),
        "PENDING_REVIEW");
  }

  private static byte[] idempotencyDigest(UUID userId, UUID workId, String key) {
    return sha256(KEY_DOMAIN + "\n" + userId + "\n" + workId + "\n" + key);
  }

  private static byte[] requestDigest(
      UUID userId,
      UUID workId,
      UUID targetChapterId,
      String ifMatch,
      Submission submission,
      List<ScopeClaim> claims) {
    String canonical =
        REQUEST_DOMAIN
            + "\n"
            + userId
            + "\n"
            + workId
            + "\n"
            + Objects.toString(targetChapterId, "whole-work")
            + "\n"
            + submission.policyVersion()
            + "\n"
            + ifMatch
            + "\n"
            + String.join(
                "\n",
                claims.stream()
                    .map(claim -> claim.scopeCode() + "=" + claim.rightsBasisCode())
                    .toList());
    return sha256(canonical);
  }

  private static byte[] withdrawalIdempotencyDigest(UUID userId, UUID workId, String key) {
    return sha256(WITHDRAWAL_KEY_DOMAIN + "\n" + userId + "\n" + workId + "\n" + key);
  }

  private static byte[] withdrawalRequestDigest(
      UUID userId, UUID workId, String expectedSnapshotEtag) {
    return sha256(
        WITHDRAWAL_REQUEST_DOMAIN
            + "\n"
            + userId
            + "\n"
            + workId
            + "\nWITHDRAW_CURRENT_PENDING\n"
            + expectedSnapshotEtag);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
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

  private record PolicyDocument(String version, Instant effectiveAt, byte[] sha256) {
    private PolicyDocument {
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }
  }

  private record Acceptance(byte[] policyDigest, Instant acceptedAt) {
    private Acceptance {
      policyDigest = policyDigest.clone();
    }

    @Override
    public byte[] policyDigest() {
      return policyDigest.clone();
    }
  }

  private record WorkSnapshot(
      long contentVersion,
      int readyChapterCount,
      String metadataSnapshot,
      String chapterTextSnapshot,
      String commercialSaleSnapshot,
      String translationAdaptationSnapshot,
      String freePreviewSnapshot) {}

  private record RequestHistory(
      UUID id, int requestVersion, byte[] snapshotDigest, String state, int eventSequence) {
    private RequestHistory {
      snapshotDigest = snapshotDigest.clone();
    }

    @Override
    public byte[] snapshotDigest() {
      return snapshotDigest.clone();
    }
  }

  private record WithdrawalReceiptRow(byte[] requestDigest, WithdrawalReceipt receipt) {
    private WithdrawalReceiptRow {
      requestDigest = requestDigest.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }
  }

  private record RequestRow(
      UUID id,
      int requestVersion,
      String policyVersion,
      byte[] snapshotDigest,
      byte[] requestDigest,
      Instant submittedAt) {
    private RequestRow {
      snapshotDigest = snapshotDigest.clone();
      requestDigest = requestDigest.clone();
    }

    @Override
    public byte[] snapshotDigest() {
      return snapshotDigest.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }
  }

  private record ScopeSnapshot(String scopeCode, String snapshotJson, byte[] sha256, String etag)
      implements Comparable<ScopeSnapshot> {
    private ScopeSnapshot {
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }

    @Override
    public int compareTo(ScopeSnapshot other) {
      return Integer.compare(scopeOrder(scopeCode), scopeOrder(other.scopeCode));
    }
  }

  public record ScopeClaim(String scopeCode, String rightsBasisCode)
      implements Comparable<ScopeClaim> {
    @Override
    public int compareTo(ScopeClaim other) {
      return Integer.compare(scopeOrder(scopeCode), scopeOrder(other.scopeCode));
    }
  }

  public record Submission(String policyVersion, List<ScopeClaim> claims) {
    public Submission {
      claims = claims == null ? List.of() : List.copyOf(claims);
    }
  }

  public record PublicationRequestReceipt(
      UUID requestId,
      int requestVersion,
      String policyVersion,
      String snapshotSha256,
      List<String> scopeCodes,
      Instant submittedAt,
      String state) {}

  public record WithdrawalReceipt(
      UUID eventId, UUID requestId, int requestVersion, String state, Instant withdrawnAt) {}

  public enum WithdrawalOutcome {
    CREATED,
    REPLAYED,
    WORK_NOT_FOUND,
    KEY_REUSED,
    STALE_SNAPSHOT,
    NOT_WITHDRAWABLE
  }

  public record WithdrawalResult(
      WithdrawalOutcome outcome,
      WithdrawalReceipt receipt,
      String currentSnapshotEtag,
      String currentState) {
    static WithdrawalResult created(WithdrawalReceipt receipt) {
      return new WithdrawalResult(WithdrawalOutcome.CREATED, receipt, null, null);
    }

    static WithdrawalResult replayed(WithdrawalReceipt receipt) {
      return new WithdrawalResult(WithdrawalOutcome.REPLAYED, receipt, null, null);
    }

    static WithdrawalResult workNotFound() {
      return new WithdrawalResult(WithdrawalOutcome.WORK_NOT_FOUND, null, null, null);
    }

    static WithdrawalResult keyReused() {
      return new WithdrawalResult(WithdrawalOutcome.KEY_REUSED, null, null, null);
    }

    static WithdrawalResult staleSnapshot(String currentSnapshotEtag) {
      return new WithdrawalResult(
          WithdrawalOutcome.STALE_SNAPSHOT, null, currentSnapshotEtag, null);
    }

    static WithdrawalResult notWithdrawable(String currentState) {
      return new WithdrawalResult(WithdrawalOutcome.NOT_WITHDRAWABLE, null, null, currentState);
    }
  }

  public enum Outcome {
    CREATED,
    REPLAYED,
    WORK_NOT_FOUND,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_ACCEPTANCE_REQUIRED,
    STALE_POLICY,
    KEY_REUSED,
    STALE_SNAPSHOT,
    READY_CHAPTER_REQUIRED,
    REQUEST_ALREADY_PENDING,
    RATE_LIMITED
  }

  public record SubmitResult(
      Outcome outcome,
      PublicationRequestReceipt receipt,
      String currentPolicyVersion,
      String currentSnapshotEtag,
      int retryAfterSeconds) {
    static SubmitResult created(PublicationRequestReceipt receipt) {
      return new SubmitResult(Outcome.CREATED, receipt, null, null, 0);
    }

    static SubmitResult replayed(PublicationRequestReceipt receipt) {
      return new SubmitResult(Outcome.REPLAYED, receipt, null, null, 0);
    }

    static SubmitResult workNotFound() {
      return new SubmitResult(Outcome.WORK_NOT_FOUND, null, null, null, 0);
    }

    static SubmitResult policyRequired() {
      return new SubmitResult(Outcome.POLICY_REQUIRED, null, null, null, 0);
    }

    static SubmitResult policyNotConfigured() {
      return new SubmitResult(Outcome.POLICY_NOT_CONFIGURED, null, null, null, 0);
    }

    static SubmitResult rightsPolicyNotConfigured() {
      return new SubmitResult(Outcome.RIGHTS_POLICY_NOT_CONFIGURED, null, null, null, 0);
    }

    static SubmitResult rightsPolicyAcceptanceRequired() {
      return new SubmitResult(Outcome.RIGHTS_POLICY_ACCEPTANCE_REQUIRED, null, null, null, 0);
    }

    static SubmitResult stalePolicy(String currentVersion) {
      return new SubmitResult(Outcome.STALE_POLICY, null, currentVersion, null, 0);
    }

    static SubmitResult keyReused() {
      return new SubmitResult(Outcome.KEY_REUSED, null, null, null, 0);
    }

    static SubmitResult staleSnapshot(String currentEtag) {
      return new SubmitResult(Outcome.STALE_SNAPSHOT, null, null, currentEtag, 0);
    }

    static SubmitResult readyChapterRequired() {
      return new SubmitResult(Outcome.READY_CHAPTER_REQUIRED, null, null, null, 0);
    }

    static SubmitResult requestAlreadyPending() {
      return new SubmitResult(Outcome.REQUEST_ALREADY_PENDING, null, null, null, 0);
    }

    static SubmitResult rateLimited(int retryAfterSeconds) {
      return new SubmitResult(Outcome.RATE_LIMITED, null, null, null, retryAfterSeconds);
    }
  }
}
