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

/** Appends one assigned-reviewer decision and, for approval only, exact scope approvals. */
@Service
@Profile("postgres")
public class PublicationReviewDecisionService {

  private final DataSource dataSource;
  private final PolicyService policyService;

  public PublicationReviewDecisionService(DataSource dataSource, PolicyService policyService) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
  }

  public DecisionResult decide(
      UUID reviewerId,
      UUID requestId,
      String expectedEtag,
      String idempotencyKey,
      DecisionCommand command) {
    Objects.requireNonNull(reviewerId, "reviewer id is required");
    Objects.requireNonNull(requestId, "request id is required");
    Objects.requireNonNull(expectedEtag, "decision ETag is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");
    Objects.requireNonNull(command, "decision is required");

    if (!hasActiveReviewerRole(reviewerId)) return DecisionResult.reviewerRequired();
    PolicyService.RequiredAcceptance initialAcceptance =
        policyService.requiredAcceptance(reviewerId);
    if (initialAcceptance != PolicyService.RequiredAcceptance.COMPLETE) {
      return initialAcceptance == PolicyService.RequiredAcceptance.NOT_CONFIGURED
          ? DecisionResult.policyNotConfigured()
          : DecisionResult.policyRequired();
    }

    byte[] keyDigest = idempotencyDigest(reviewerId, requestId, idempotencyKey);
    byte[] requestDigest = requestDigest(reviewerId, requestId, expectedEtag, command);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<LockedRequest> locked = lockEligibleRequest(connection, reviewerId, requestId);
        if (locked.isEmpty()) {
          connection.commit();
          return DecisionResult.notFound();
        }
        LockedRequest request = locked.orElseThrow();

        if (!lockActiveReviewerRole(connection, reviewerId)) {
          connection.commit();
          return DecisionResult.reviewerRequired();
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

        try (PreparedStatement policyLock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          policyLock.execute();
        }
        Optional<CurrentRightsPolicy> currentPolicy = currentRightsPolicy(connection);

        Optional<DecisionReceipt> prior =
            findIdempotentDecision(connection, reviewerId, requestId, keyDigest);
        if (prior.isPresent()) {
          DecisionReceipt receipt = prior.orElseThrow();
          if (!MessageDigest.isEqual(receipt.requestDigest(), requestDigest)) {
            connection.commit();
            return DecisionResult.keyReused();
          }
          CurrentEvent latest = latestEvent(connection, requestId);
          String currentEtag = etag(request, latest, currentPolicy.orElse(null));
          connection.commit();
          return DecisionResult.replayed(receipt.publicReceipt(), currentEtag);
        }

        CurrentEvent currentEvent = latestEvent(connection, requestId);
        String currentEtag = etag(request, currentEvent, currentPolicy.orElse(null));
        if (!currentEtag.equals(expectedEtag)) {
          connection.commit();
          return DecisionResult.staleEtag(currentEtag);
        }
        if (!"PENDING_REVIEW".equals(currentEvent.state())) {
          connection.commit();
          return DecisionResult.invalidState(currentEtag, currentEvent.state());
        }

        List<ScopeSnapshot> snapshots = readScopeSnapshots(connection, request);
        if (snapshots.isEmpty() || !snapshotDigest(snapshots, request.snapshotDigest())) {
          connection.commit();
          return DecisionResult.storeUnavailable();
        }
        boolean currentSnapshot = snapshots.stream().allMatch(ScopeSnapshot::current);
        if (command.actionCode().equals("APPROVED") && !currentSnapshot) {
          connection.commit();
          return DecisionResult.staleSnapshot(currentEtag);
        }

        CurrentRightsPolicy policy = currentPolicy.orElse(null);
        if (command.actionCode().equals("APPROVED")) {
          if (policy == null) {
            connection.commit();
            return DecisionResult.rightsPolicyNotConfigured();
          }
          if (!policy.version().equals(request.policyVersion())
              || !MessageDigest.isEqual(policy.sha256(), request.policyDigest())) {
            connection.commit();
            return DecisionResult.rightsPolicyStale(policy.version(), currentEtag);
          }
          if (hasUnresolvedClaim(connection, requestId)) {
            connection.commit();
            return DecisionResult.unresolvedClaim();
          }
        }

        int sequence = currentEvent.sequence() + 1;
        String toState = toState(command.actionCode());
        DecisionReceipt receipt =
            appendDecision(
                connection,
                reviewerId,
                request,
                sequence,
                currentEvent.state(),
                toState,
                keyDigest,
                requestDigest,
                command);
        if (command.actionCode().equals("APPROVED") && request.targetChapterId() == null) {
          insertScopeApprovals(
              connection, reviewerId, request, snapshots, policy, command.reasonCode());
        }

        String decisionEtag =
            PublicationReviewEtags.decision(
                request.id(),
                request.requestVersion(),
                receipt.eventSequence(),
                receipt.toState(),
                HexFormat.of().formatHex(request.snapshotDigest()),
                request.currentContentVersion(),
                policy == null ? null : policy.version(),
                policy == null ? null : HexFormat.of().formatHex(policy.sha256()));
        connection.commit();
        return DecisionResult.created(receipt.publicReceipt(), decisionEtag);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record publication review decision", exception);
    }
  }

  private boolean hasActiveReviewerRole(UUID reviewerId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT EXISTS (
                        SELECT 1
                          FROM security_role_assignments assignment
                          JOIN users reviewer
                            ON reviewer.id = assignment.user_id AND reviewer.status = 'active'
                         WHERE assignment.user_id = ?
                           AND assignment.role_code = 'publication_reviewer'
                           AND assignment.assigned_at <= clock_timestamp()
                           AND assignment.revoked_at IS NULL
                    )
                    """)) {
      select.setObject(1, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getBoolean(1);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to check current reviewer assignment", exception);
    }
  }

  private static Optional<LockedRequest> lockEligibleRequest(
      Connection connection, UUID reviewerId, UUID requestId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request.id, request.work_id, request.owner_user_id,
                       request.request_version, request.policy_version, request.policy_sha256,
                       request.snapshot_sha256, request.target_chapter_id,
                       work.publication_content_version
                  FROM publication_requests request
                  JOIN works work
                    ON work.id = request.work_id AND work.owner_user_id = request.owner_user_id
                  JOIN users owner
                    ON owner.id = request.owner_user_id AND owner.status = 'active'
                  JOIN libraries library
                    ON library.id = work.library_id AND library.owner_user_id = work.owner_user_id
                  JOIN authoring_manuscript_creations creation
                    ON creation.work_id = work.id AND creation.actor_user_id = work.owner_user_id
                 WHERE request.id = ? AND request.owner_user_id <> ?
                   AND work.source_kind = 'authored'
                   AND ((request.target_chapter_id IS NULL AND work.visibility = 'private')
                     OR (request.target_chapter_id IS NOT NULL AND work.visibility = 'public'))
                   AND work.status = 'active' AND work.deleted_at IS NULL
                   AND library.workspace_id IS NULL AND library.deleted_at IS NULL
                 FOR UPDATE OF work, owner, library, creation, request
                """)) {
      select.setObject(1, requestId);
      select.setObject(2, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new LockedRequest(
                row.getObject("id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getObject("owner_user_id", UUID.class),
                row.getInt("request_version"),
                row.getString("policy_version"),
                row.getBytes("policy_sha256"),
                row.getBytes("snapshot_sha256"),
                row.getObject("target_chapter_id", UUID.class),
                row.getLong("publication_content_version")));
      }
    }
  }

  private static boolean lockActiveReviewerRole(Connection connection, UUID reviewerId)
      throws SQLException {
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
                 FOR UPDATE OF assignment, reviewer
                """)) {
      select.setObject(1, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static Optional<CurrentRightsPolicy> currentRightsPolicy(Connection connection)
      throws SQLException {
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT version, policy_sha256
                  FROM publication_rights_policy_documents
                 WHERE status = 'APPROVED'
                   AND effective_at <= clock_timestamp()
                 ORDER BY effective_at DESC, version DESC
                 LIMIT 1
                """);
        ResultSet row = select.executeQuery()) {
      if (!row.next()) return Optional.empty();
      return Optional.of(
          new CurrentRightsPolicy(row.getString("version"), row.getBytes("policy_sha256")));
    }
  }

  private static CurrentEvent latestEvent(Connection connection, UUID requestId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event_sequence, to_state
                  FROM publication_review_events
                 WHERE request_id = ?
                 ORDER BY event_sequence DESC, id DESC
                 LIMIT 1
                """)) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Publication request has no initial event");
        return new CurrentEvent(row.getInt("event_sequence"), row.getString("to_state"));
      }
    }
  }

  private static Optional<DecisionReceipt> findIdempotentDecision(
      Connection connection, UUID reviewerId, UUID requestId, byte[] keyDigest)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event_sequence, actor_role_code, action_code, from_state, to_state,
                       reason_code, created_at, request_sha256
                  FROM publication_review_events
                 WHERE request_id = ? AND actor_user_id = ? AND idempotency_key_sha256 = ?
                """)) {
      select.setObject(1, requestId);
      select.setObject(2, reviewerId);
      select.setBytes(3, keyDigest);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(readDecisionReceipt(row));
      }
    }
  }

  private static List<ScopeSnapshot> readScopeSnapshots(
      Connection connection, LockedRequest request) throws SQLException {
    String snapshotExpression =
        request.targetChapterId() == null
            ? "publication_work_scope_snapshot(?, scope.scope_code)"
            : "publication_work_scope_snapshot_for_chapter(?, scope.scope_code, ?)";
    String sql =
        """
            SELECT scope.scope_code, scope.scope_snapshot::text AS scope_snapshot,
                   scope.snapshot_sha256, %s::text AS current_snapshot
              FROM publication_request_scope_snapshots scope
             WHERE scope.request_id = ?
             ORDER BY CASE scope.scope_code WHEN 'PUBLIC_METADATA' THEN 0
                  WHEN 'CHAPTER_TEXT' THEN 1 WHEN 'COMMERCIAL_SALE' THEN 2
                  WHEN 'TRANSLATION_ADAPTATION' THEN 3 ELSE 4 END
            """
            .formatted(snapshotExpression);
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, request.workId());
      if (request.targetChapterId() != null) select.setObject(2, request.targetChapterId());
      select.setObject(request.targetChapterId() == null ? 2 : 3, request.id());
      try (ResultSet rows = select.executeQuery()) {
        List<ScopeSnapshot> snapshots = new ArrayList<>();
        while (rows.next()) {
          String json = rows.getString("scope_snapshot");
          byte[] storedDigest = rows.getBytes("snapshot_sha256");
          byte[] computedDigest = sha256(json);
          if (!MessageDigest.isEqual(storedDigest, computedDigest)) {
            throw new IllegalStateException("Stored publication scope digest is inconsistent");
          }
          String currentJson = rows.getString("current_snapshot");
          snapshots.add(
              new ScopeSnapshot(
                  rows.getString("scope_code"),
                  json,
                  currentJson != null && json.equals(currentJson)));
        }
        return List.copyOf(snapshots);
      }
    }
  }

  private static boolean snapshotDigest(List<ScopeSnapshot> snapshots, byte[] expectedDigest) {
    String canonical =
        String.join(
            "\n",
            snapshots.stream()
                .sorted(
                    (left, right) ->
                        Integer.compare(
                            scopeOrder(left.scopeCode()), scopeOrder(right.scopeCode())))
                .map(scope -> scope.scopeCode() + "\n" + scope.json())
                .toList());
    return MessageDigest.isEqual(sha256(canonical), expectedDigest);
  }

  private static boolean hasUnresolvedClaim(Connection connection, UUID requestId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT EXISTS (
                    SELECT 1 FROM publication_request_scope_claims
                     WHERE request_id = ? AND rights_basis_code = 'NEEDS_REVIEW'
                )
                """)) {
      select.setObject(1, requestId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getBoolean(1);
      }
    }
  }

  private static DecisionReceipt appendDecision(
      Connection connection,
      UUID reviewerId,
      LockedRequest request,
      int eventSequence,
      String fromState,
      String toState,
      byte[] keyDigest,
      byte[] requestDigest,
      DecisionCommand command)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_review_events (
                    id, request_id, event_sequence, actor_user_id, actor_role_code,
                    action_code, from_state, to_state, reason_code,
                    idempotency_key_sha256, request_sha256, snapshot_sha256)
                VALUES (?, ?, ?, ?, 'publication_reviewer', ?, ?, ?, ?, ?, ?, ?)
                RETURNING event_sequence, actor_role_code, action_code, from_state, to_state,
                          reason_code, created_at, request_sha256
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, request.id());
      insert.setInt(3, eventSequence);
      insert.setObject(4, reviewerId);
      insert.setString(5, command.actionCode());
      insert.setString(6, fromState);
      insert.setString(7, toState);
      insert.setString(8, command.reasonCode());
      insert.setBytes(9, keyDigest);
      insert.setBytes(10, requestDigest);
      insert.setBytes(11, request.snapshotDigest());
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Review decision event insert returned no receipt");
        return readDecisionReceipt(row);
      }
    }
  }

  private static void insertScopeApprovals(
      Connection connection,
      UUID reviewerId,
      LockedRequest request,
      List<ScopeSnapshot> snapshots,
      CurrentRightsPolicy policy,
      String reasonCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_scope_access_approvals (
                    id, work_id, scope_code, approved_snapshot, policy_version, policy_sha256,
                    approved_by_user_id, reason_code)
                VALUES (?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?)
                """)) {
      for (ScopeSnapshot snapshot : snapshots) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, request.workId());
        insert.setString(3, snapshot.scopeCode());
        insert.setString(4, snapshot.json());
        insert.setString(5, policy.version());
        insert.setBytes(6, policy.sha256());
        insert.setObject(7, reviewerId);
        insert.setString(8, reasonCode);
        if (insert.executeUpdate() != 1) {
          throw new SQLException("Publication scope approval was not recorded");
        }
      }
    }
  }

  private static DecisionReceipt readDecisionReceipt(ResultSet row) throws SQLException {
    return new DecisionReceipt(
        row.getInt("event_sequence"),
        row.getString("actor_role_code"),
        row.getString("action_code"),
        row.getString("from_state"),
        row.getString("to_state"),
        row.getString("reason_code"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getBytes("request_sha256"));
  }

  private static String etag(
      LockedRequest request, CurrentEvent latestEvent, CurrentRightsPolicy policy) {
    return PublicationReviewEtags.decision(
        request.id(),
        request.requestVersion(),
        latestEvent.sequence(),
        latestEvent.state(),
        HexFormat.of().formatHex(request.snapshotDigest()),
        request.currentContentVersion(),
        policy == null ? null : policy.version(),
        policy == null ? null : HexFormat.of().formatHex(policy.sha256()));
  }

  private static String toState(String actionCode) {
    return switch (actionCode) {
      case "NEEDS_INFORMATION" -> "NEEDS_INFORMATION";
      case "APPROVED" -> "APPROVED_UNPUBLISHED";
      case "REJECTED" -> "REJECTED";
      default -> throw new IllegalArgumentException("Unsupported publication-review decision");
    };
  }

  private static int scopeOrder(String scopeCode) {
    return switch (scopeCode) {
      case "PUBLIC_METADATA" -> 0;
      case "CHAPTER_TEXT" -> 1;
      case "COMMERCIAL_SALE" -> 2;
      case "TRANSLATION_ADAPTATION" -> 3;
      default -> 4;
    };
  }

  private static byte[] idempotencyDigest(UUID reviewerId, UUID requestId, String key) {
    return sha256(
        String.join(
            "\n",
            "publication-review-decision-idempotency-v1",
            reviewerId.toString(),
            requestId.toString(),
            key));
  }

  private static byte[] requestDigest(
      UUID reviewerId, UUID requestId, String ifMatch, DecisionCommand command) {
    return sha256(
        String.join(
            "\n",
            "publication-review-decision-request-v1",
            reviewerId.toString(),
            requestId.toString(),
            ifMatch,
            command.actionCode(),
            command.reasonCode()));
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

  private record LockedRequest(
      UUID id,
      UUID workId,
      UUID ownerId,
      int requestVersion,
      String policyVersion,
      byte[] policyDigest,
      byte[] snapshotDigest,
      UUID targetChapterId,
      long currentContentVersion) {
    private LockedRequest {
      policyDigest = policyDigest.clone();
      snapshotDigest = snapshotDigest.clone();
    }

    @Override
    public byte[] policyDigest() {
      return policyDigest.clone();
    }

    @Override
    public byte[] snapshotDigest() {
      return snapshotDigest.clone();
    }
  }

  private record CurrentEvent(int sequence, String state) {}

  private record CurrentRightsPolicy(String version, byte[] sha256) {
    private CurrentRightsPolicy {
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }
  }

  private record ScopeSnapshot(String scopeCode, String json, boolean current) {}

  private record DecisionReceipt(
      int eventSequence,
      String actorRoleCode,
      String actionCode,
      String fromState,
      String toState,
      String reasonCode,
      Instant createdAt,
      byte[] requestDigest) {
    private DecisionReceipt {
      requestDigest = requestDigest.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }

    DecisionReceiptBody publicReceipt() {
      return new DecisionReceiptBody(
          eventSequence, actorRoleCode, actionCode, fromState, toState, reasonCode, createdAt);
    }
  }

  public record DecisionCommand(String actionCode, String reasonCode) {
    public DecisionCommand {
      Objects.requireNonNull(actionCode, "decision action is required");
      Objects.requireNonNull(reasonCode, "decision reason is required");
    }
  }

  public record DecisionReceiptBody(
      int eventSequence,
      String actorRoleCode,
      String actionCode,
      String fromState,
      String toState,
      String reasonCode,
      Instant decidedAt) {}

  public record DecisionResult(
      Outcome outcome,
      DecisionReceiptBody receipt,
      String etag,
      String currentState,
      String policyVersion) {
    static DecisionResult created(DecisionReceiptBody receipt, String etag) {
      return new DecisionResult(Outcome.CREATED, receipt, etag, null, null);
    }

    static DecisionResult replayed(DecisionReceiptBody receipt, String etag) {
      return new DecisionResult(Outcome.REPLAYED, receipt, etag, null, null);
    }

    static DecisionResult reviewerRequired() {
      return new DecisionResult(Outcome.REVIEWER_REQUIRED, null, null, null, null);
    }

    static DecisionResult policyRequired() {
      return new DecisionResult(Outcome.POLICY_REQUIRED, null, null, null, null);
    }

    static DecisionResult policyNotConfigured() {
      return new DecisionResult(Outcome.POLICY_NOT_CONFIGURED, null, null, null, null);
    }

    static DecisionResult notFound() {
      return new DecisionResult(Outcome.NOT_FOUND, null, null, null, null);
    }

    static DecisionResult keyReused() {
      return new DecisionResult(Outcome.KEY_REUSED, null, null, null, null);
    }

    static DecisionResult staleEtag(String etag) {
      return new DecisionResult(Outcome.STALE_ETAG, null, etag, null, null);
    }

    static DecisionResult invalidState(String etag, String state) {
      return new DecisionResult(Outcome.INVALID_STATE, null, etag, state, null);
    }

    static DecisionResult staleSnapshot(String etag) {
      return new DecisionResult(Outcome.STALE_SNAPSHOT, null, etag, null, null);
    }

    static DecisionResult rightsPolicyNotConfigured() {
      return new DecisionResult(Outcome.RIGHTS_POLICY_NOT_CONFIGURED, null, null, null, null);
    }

    static DecisionResult rightsPolicyStale(String policyVersion, String etag) {
      return new DecisionResult(Outcome.RIGHTS_POLICY_STALE, null, etag, null, policyVersion);
    }

    static DecisionResult unresolvedClaim() {
      return new DecisionResult(Outcome.UNRESOLVED_CLAIM, null, null, null, null);
    }

    static DecisionResult storeUnavailable() {
      return new DecisionResult(Outcome.STORE_UNAVAILABLE, null, null, null, null);
    }
  }

  public enum Outcome {
    CREATED,
    REPLAYED,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    NOT_FOUND,
    KEY_REUSED,
    STALE_ETAG,
    INVALID_STATE,
    STALE_SNAPSHOT,
    RIGHTS_POLICY_NOT_CONFIGURED,
    RIGHTS_POLICY_STALE,
    UNRESOLVED_CLAIM,
    STORE_UNAVAILABLE
  }
}
