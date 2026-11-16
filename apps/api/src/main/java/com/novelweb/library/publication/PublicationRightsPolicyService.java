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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Loads approved rights-policy text and records owner/work-scoped publication acknowledgements. */
@Service
@Profile("postgres")
public class PublicationRightsPolicyService {

  private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final String REQUEST_DOMAIN = "publication-rights-policy-acceptance-v1";

  private final DataSource dataSource;
  private final PolicyService policyService;

  public PublicationRightsPolicyService(DataSource dataSource, PolicyService policyService) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
  }

  public PolicyReadResult current(UUID userId, UUID workId) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockEligibleWork(connection, userId, workId)) {
          connection.commit();
          return PolicyReadResult.workNotFound();
        }

        PolicyService.RequiredAcceptance acceptanceState = policyService.requiredAcceptance(userId);
        if (acceptanceState == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return PolicyReadResult.policyRequired();
        }
        if (acceptanceState == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return PolicyReadResult.policyNotConfigured();
        }

        Optional<PolicyDocument> active = currentPolicy(connection);
        if (active.isEmpty()) {
          connection.commit();
          return PolicyReadResult.notConfigured();
        }
        PolicyDocument policy = active.orElseThrow();
        Optional<Acceptance> acceptance =
            findAcceptance(connection, userId, workId, policy.version());
        connection.commit();
        return PolicyReadResult.available(
            new CurrentPolicy(
                policy.version(),
                policy.effectiveAt(),
                policy.displayText(),
                HexFormat.of().formatHex(policy.sha256()),
                acceptance.map(Acceptance::acceptedAt).orElse(null)));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load publication-rights policy", exception);
    }
  }

  /**
   * Returns an owner-only checklist and opaque snapshot validators without returning manuscript
   * text.
   */
  public ReadinessResult readiness(UUID userId, UUID workId) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockEligibleWorkForRead(connection, userId, workId)) {
          connection.commit();
          return ReadinessResult.workNotFound();
        }

        PolicyService.RequiredAcceptance acceptanceState = policyService.requiredAcceptance(userId);
        if (acceptanceState == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return ReadinessResult.policyRequired();
        }
        if (acceptanceState == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return ReadinessResult.policyNotConfigured();
        }

        // The selected rights-policy row and content snapshots stay stable for this response.
        try (PreparedStatement lock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lock.execute();
        }
        Optional<PolicyDocument> activePolicy = currentPolicy(connection);
        Acceptance acceptedPolicy = null;
        if (activePolicy.isPresent()) {
          acceptedPolicy =
              findAcceptance(connection, userId, workId, activePolicy.orElseThrow().version())
                  .orElse(null);
        }
        ReadinessSnapshot snapshot = readReadinessSnapshot(connection, workId);
        connection.commit();

        List<String> blockers = new java.util.ArrayList<>();
        if (activePolicy.isEmpty()) {
          blockers.add("PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED");
        } else if (acceptedPolicy == null) {
          blockers.add("PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED");
        }
        if (snapshot.readyChapterCount() == 0) {
          blockers.add("READY_CHAPTER_REQUIRED");
        }
        if ("PENDING_REVIEW".equals(snapshot.currentRequestState())) {
          blockers.add("PUBLICATION_REQUEST_ALREADY_PENDING");
        }

        String policyVersion = activePolicy.map(PolicyDocument::version).orElse(null);
        Instant policyEffectiveAt = activePolicy.map(PolicyDocument::effectiveAt).orElse(null);
        String policyDigest =
            activePolicy.map(policy -> HexFormat.of().formatHex(policy.sha256())).orElse("none");
        Instant acceptedAt = acceptedPolicy == null ? null : acceptedPolicy.acceptedAt();
        String metadataEtag = scopeEtag("PUBLIC_METADATA", snapshot.metadataSnapshot());
        String chapterTextEtag = scopeEtag("CHAPTER_TEXT", snapshot.chapterTextSnapshot());
        String commercialSaleEtag = scopeEtag("COMMERCIAL_SALE", snapshot.commercialSaleSnapshot());
        String snapshotEtag =
            readinessEtag(
                workId,
                snapshot,
                policyVersion,
                policyDigest,
                acceptedAt,
                metadataEtag,
                chapterTextEtag,
                commercialSaleEtag);
        return ReadinessResult.available(
            new PublicationReadiness(
                workId,
                blockers.isEmpty(),
                activePolicy.isPresent(),
                policyVersion,
                policyEffectiveAt,
                acceptedAt,
                snapshot.contentVersion(),
                snapshot.readyChapterCount(),
                snapshot.currentRequestState(),
                snapshotEtag,
                List.of(
                    new ScopeReadiness("PUBLIC_METADATA", true, metadataEtag),
                    new ScopeReadiness(
                        "CHAPTER_TEXT", snapshot.readyChapterCount() > 0, chapterTextEtag),
                    new ScopeReadiness(
                        "COMMERCIAL_SALE", snapshot.readyChapterCount() > 0, commercialSaleEtag)),
                List.copyOf(blockers)));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load publication readiness", exception);
    }
  }

  public AcceptanceResult accept(
      UUID userId, UUID workId, String requestedVersion, UUID idempotencyKey) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(requestedVersion, "policy version is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockEligibleWork(connection, userId, workId)) {
          connection.commit();
          return AcceptanceResult.workNotFound();
        }

        PolicyService.RequiredAcceptance acceptanceState = policyService.requiredAcceptance(userId);
        if (acceptanceState == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return AcceptanceResult.policyRequired();
        }
        if (acceptanceState == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return AcceptanceResult.policyNotConfigured();
        }

        // Keep approved policy selection stable through acceptance and idempotency receipt commit.
        try (PreparedStatement lock =
            connection.prepareStatement(
                "LOCK TABLE publication_rights_policy_documents IN SHARE MODE")) {
          lock.execute();
        }

        byte[] requestDigest = requestDigest(requestedVersion);
        Optional<Acceptance> priorAttempt =
            findIdempotentAcceptance(connection, userId, workId, idempotencyKey, requestDigest);
        if (priorAttempt.isPresent()) {
          connection.commit();
          return AcceptanceResult.accepted(priorAttempt.orElseThrow());
        }
        if (hasIdempotencyKey(connection, userId, workId, idempotencyKey)) {
          connection.commit();
          return AcceptanceResult.keyReused();
        }

        Optional<PolicyDocument> active = currentPolicy(connection);
        if (active.isEmpty()) {
          connection.commit();
          return AcceptanceResult.notConfigured();
        }
        PolicyDocument policy = active.orElseThrow();
        if (!policy.version().equals(requestedVersion)) {
          connection.commit();
          return AcceptanceResult.stale(policy.version());
        }

        Optional<Acceptance> existing =
            findAcceptance(connection, userId, workId, policy.version());
        Acceptance acceptance =
            existing.orElseGet(
                () -> insertAcceptance(connection, userId, workId, idempotencyKey, policy));
        insertIdempotencyReceipt(
            connection, userId, workId, idempotencyKey, requestDigest, acceptance.id());
        connection.commit();
        return AcceptanceResult.accepted(acceptance);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to record publication-rights policy acceptance", exception);
    }
  }

  public static boolean isValidVersion(String version) {
    return version != null && VERSION_PATTERN.matcher(version).matches();
  }

  private boolean lockEligibleWork(Connection connection, UUID userId, UUID workId)
      throws SQLException {
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
                  AND w.id = ? AND w.source_kind = 'authored' AND w.visibility = 'private'
                  AND w.status = 'active' AND w.deleted_at IS NULL
                  AND l.deleted_at IS NULL AND l.workspace_id IS NULL
                FOR UPDATE OF u, w, l, creation
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private boolean lockEligibleWorkForRead(Connection connection, UUID userId, UUID workId)
      throws SQLException {
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
                  AND w.id = ? AND w.source_kind = 'authored' AND w.visibility = 'private'
                  AND w.status = 'active' AND w.deleted_at IS NULL
                  AND l.deleted_at IS NULL AND l.workspace_id IS NULL
                FOR SHARE OF u, w, l, creation
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private ReadinessSnapshot readReadinessSnapshot(Connection connection, UUID workId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT w.publication_content_version,
                       (SELECT COUNT(*)
                          FROM chapters chapter
                         WHERE chapter.work_id = w.id
                           AND chapter.status = 'ready'
                           AND chapter.deleted_at IS NULL
                           AND chapter.source_revision_id IS NOT NULL) AS ready_chapter_count,
                       publication_work_scope_snapshot(w.id, 'PUBLIC_METADATA')::text
                           AS metadata_snapshot,
                       publication_work_scope_snapshot(w.id, 'CHAPTER_TEXT')::text
                           AS chapter_text_snapshot,
                       publication_work_scope_snapshot(w.id, 'COMMERCIAL_SALE')::text
                           AS commercial_sale_snapshot,
                       (SELECT state.current_state
                          FROM publication_requests request
                          JOIN publication_request_current_state state
                            ON state.request_id = request.id
                         WHERE request.work_id = w.id
                         ORDER BY request.request_version DESC, request.id DESC
                         LIMIT 1) AS current_request_state
                  FROM works w
                 WHERE w.id = ?
                """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          throw new SQLException("Eligible manuscript disappeared during readiness read");
        }
        return new ReadinessSnapshot(
            row.getLong("publication_content_version"),
            row.getInt("ready_chapter_count"),
            row.getString("metadata_snapshot"),
            row.getString("chapter_text_snapshot"),
            row.getString("commercial_sale_snapshot"),
            row.getString("current_request_state"));
      }
    }
  }

  private static String scopeEtag(String scopeCode, String snapshot) {
    return "\"publication-scope-"
        + HexFormat.of().formatHex(sha256(scopeCode + "\n" + snapshot))
        + "\"";
  }

  private static String readinessEtag(
      UUID workId,
      ReadinessSnapshot snapshot,
      String policyVersion,
      String policyDigest,
      Instant acceptedAt,
      String metadataEtag,
      String chapterTextEtag,
      String commercialSaleEtag) {
    String canonical =
        String.join(
            "\n",
            "publication-readiness-v1",
            workId.toString(),
            Long.toString(snapshot.contentVersion()),
            Integer.toString(snapshot.readyChapterCount()),
            Objects.toString(policyVersion, "none"),
            policyDigest,
            Objects.toString(acceptedAt, "none"),
            Objects.toString(snapshot.currentRequestState(), "none"),
            metadataEtag,
            chapterTextEtag,
            commercialSaleEtag);
    return "\"publication-snapshot-" + HexFormat.of().formatHex(sha256(canonical)) + "\"";
  }

  private Optional<PolicyDocument> currentPolicy(Connection connection) throws SQLException {
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
      String displayText = row.getString("display_text");
      byte[] storedDigest = row.getBytes("policy_sha256");
      if (!MessageDigest.isEqual(storedDigest, sha256(displayText))) {
        throw new SQLException("Stored publication-rights policy digest does not match its text");
      }
      return Optional.of(
          new PolicyDocument(
              row.getString("version"),
              row.getObject("effective_at", OffsetDateTime.class).toInstant(),
              displayText,
              storedDigest));
    }
  }

  private Optional<Acceptance> findAcceptance(
      Connection connection, UUID userId, UUID workId, String policyVersion) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, policy_version, policy_sha256, accepted_at
                FROM publication_rights_policy_acceptances
                WHERE user_id = ? AND work_id = ? AND policy_version = ?
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      select.setString(3, policyVersion);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(readAcceptance(row));
      }
    }
  }

  private Optional<Acceptance> findIdempotentAcceptance(
      Connection connection, UUID userId, UUID workId, UUID idempotencyKey, byte[] requestDigest)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT acceptance.id, acceptance.policy_version, acceptance.policy_sha256,
                       acceptance.accepted_at, receipt.request_sha256
                FROM publication_rights_policy_acceptance_idempotency receipt
                JOIN publication_rights_policy_acceptances acceptance
                  ON acceptance.id = receipt.acceptance_id
                 AND acceptance.user_id = receipt.user_id AND acceptance.work_id = receipt.work_id
                WHERE receipt.user_id = ? AND receipt.work_id = ? AND receipt.idempotency_key = ?
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      select.setObject(3, idempotencyKey);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next() || !MessageDigest.isEqual(row.getBytes("request_sha256"), requestDigest)) {
          return Optional.empty();
        }
        return Optional.of(readAcceptance(row));
      }
    }
  }

  private boolean hasIdempotencyKey(
      Connection connection, UUID userId, UUID workId, UUID idempotencyKey) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1 FROM publication_rights_policy_acceptance_idempotency
                WHERE user_id = ? AND work_id = ? AND idempotency_key = ?
                """)) {
      select.setObject(1, userId);
      select.setObject(2, workId);
      select.setObject(3, idempotencyKey);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private Acceptance insertAcceptance(
      Connection connection, UUID userId, UUID workId, UUID idempotencyKey, PolicyDocument policy) {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_rights_policy_acceptances
                    (id, user_id, work_id, idempotency_key, policy_version, policy_sha256, source)
                VALUES (?, ?, ?, ?, ?, ?, 'web-authoring-publication')
                RETURNING id, policy_version, policy_sha256, accepted_at
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, userId);
      insert.setObject(3, workId);
      insert.setObject(4, idempotencyKey);
      insert.setString(5, policy.version());
      insert.setBytes(6, policy.sha256());
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Publication-rights acceptance was not recorded");
        return readAcceptance(row);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create publication-rights acceptance", exception);
    }
  }

  private void insertIdempotencyReceipt(
      Connection connection,
      UUID userId,
      UUID workId,
      UUID idempotencyKey,
      byte[] requestDigest,
      UUID acceptanceId) {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_rights_policy_acceptance_idempotency
                    (user_id, work_id, idempotency_key, request_sha256, acceptance_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, userId);
      insert.setObject(2, workId);
      insert.setObject(3, idempotencyKey);
      insert.setBytes(4, requestDigest);
      insert.setObject(5, acceptanceId);
      if (insert.executeUpdate() != 1) {
        throw new SQLException("Publication-rights idempotency receipt was not recorded");
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to create publication-rights idempotency receipt", exception);
    }
  }

  private static Acceptance readAcceptance(ResultSet row) throws SQLException {
    return new Acceptance(
        row.getObject("id", UUID.class),
        row.getString("policy_version"),
        HexFormat.of().formatHex(row.getBytes("policy_sha256")),
        row.getObject("accepted_at", OffsetDateTime.class).toInstant());
  }

  private static byte[] requestDigest(String version) {
    try {
      String canonical = REQUEST_DOMAIN + "\n" + version + "\nconfirmed=true";
      return MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
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

  private record PolicyDocument(
      String version, Instant effectiveAt, String displayText, byte[] sha256) {
    private PolicyDocument {
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }
  }

  private record ReadinessSnapshot(
      long contentVersion,
      int readyChapterCount,
      String metadataSnapshot,
      String chapterTextSnapshot,
      String commercialSaleSnapshot,
      String currentRequestState) {}

  public record CurrentPolicy(
      String version,
      Instant effectiveAt,
      String displayText,
      String policySha256,
      Instant acceptedAt) {}

  public record PublicationReadiness(
      UUID workId,
      boolean canSubmit,
      boolean publicationRightsPolicyAvailable,
      String publicationRightsPolicyVersion,
      Instant publicationRightsPolicyEffectiveAt,
      Instant publicationRightsPolicyAcceptedAt,
      long publicationContentVersion,
      int readyChapterCount,
      String currentRequestState,
      String snapshotEtag,
      List<ScopeReadiness> scopes,
      List<String> blockerCodes) {}

  public record ScopeReadiness(String scopeCode, boolean ready, String snapshotEtag) {}

  public record ReadinessResult(Outcome outcome, PublicationReadiness readiness) {
    static ReadinessResult available(PublicationReadiness readiness) {
      return new ReadinessResult(Outcome.AVAILABLE, readiness);
    }

    static ReadinessResult policyRequired() {
      return new ReadinessResult(Outcome.POLICY_REQUIRED, null);
    }

    static ReadinessResult policyNotConfigured() {
      return new ReadinessResult(Outcome.POLICY_NOT_CONFIGURED, null);
    }

    static ReadinessResult workNotFound() {
      return new ReadinessResult(Outcome.WORK_NOT_FOUND, null);
    }
  }

  public record Acceptance(
      UUID id, String policyVersion, String policySha256, Instant acceptedAt) {}

  public enum Outcome {
    AVAILABLE,
    ACCEPTED,
    STALE,
    KEY_REUSED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    NOT_CONFIGURED,
    WORK_NOT_FOUND
  }

  public record PolicyReadResult(Outcome outcome, CurrentPolicy policy) {
    static PolicyReadResult available(CurrentPolicy policy) {
      return new PolicyReadResult(Outcome.AVAILABLE, policy);
    }

    static PolicyReadResult notConfigured() {
      return new PolicyReadResult(Outcome.NOT_CONFIGURED, null);
    }

    static PolicyReadResult policyRequired() {
      return new PolicyReadResult(Outcome.POLICY_REQUIRED, null);
    }

    static PolicyReadResult policyNotConfigured() {
      return new PolicyReadResult(Outcome.POLICY_NOT_CONFIGURED, null);
    }

    static PolicyReadResult workNotFound() {
      return new PolicyReadResult(Outcome.WORK_NOT_FOUND, null);
    }
  }

  public record AcceptanceResult(Outcome outcome, Acceptance acceptance, String currentVersion) {
    static AcceptanceResult accepted(Acceptance acceptance) {
      return new AcceptanceResult(Outcome.ACCEPTED, acceptance, null);
    }

    static AcceptanceResult stale(String currentVersion) {
      return new AcceptanceResult(Outcome.STALE, null, currentVersion);
    }

    static AcceptanceResult keyReused() {
      return new AcceptanceResult(Outcome.KEY_REUSED, null, null);
    }

    static AcceptanceResult policyRequired() {
      return new AcceptanceResult(Outcome.POLICY_REQUIRED, null, null);
    }

    static AcceptanceResult policyNotConfigured() {
      return new AcceptanceResult(Outcome.POLICY_NOT_CONFIGURED, null, null);
    }

    static AcceptanceResult notConfigured() {
      return new AcceptanceResult(Outcome.NOT_CONFIGURED, null, null);
    }

    static AcceptanceResult workNotFound() {
      return new AcceptanceResult(Outcome.WORK_NOT_FOUND, null, null);
    }
  }
}
