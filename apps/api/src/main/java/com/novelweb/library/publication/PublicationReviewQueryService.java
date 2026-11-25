package com.novelweb.library.publication;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.library.LibraryCursorCodec;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read-only, current-role-authorized review queue and privacy-filtered request preview. */
@Service
@Profile("postgres")
public class PublicationReviewQueryService {

  private static final int MAX_PAGE_SIZE = 50;

  private final DataSource dataSource;
  private final PolicyService policyService;
  private final LibraryCursorCodec cursorCodec;
  private final ObjectMapper objectMapper;

  public PublicationReviewQueryService(
      DataSource dataSource,
      PolicyService policyService,
      LibraryCursorCodec cursorCodec,
      ObjectMapper objectMapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.policyService = Objects.requireNonNull(policyService, "policy service is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursor codec is required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "JSON mapper is required");
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
            LibraryCursorCodec.publicationReviewQueueContext(reviewerId);
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
                        0,
                        items.get(items.size() - 1).submittedAt(),
                        items.get(items.size() - 1).requestId()))
                : null;
        connection.commit();
        return QueueResult.available(new QueuePage(items, nextCursor));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load publication review queue", exception);
    }
  }

  public DetailResult detail(UUID reviewerId, UUID requestId) {
    Objects.requireNonNull(reviewerId, "reviewer id is required");
    Objects.requireNonNull(requestId, "request id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try {
        Gate gate = gate(connection, reviewerId);
        if (gate != Gate.AVAILABLE) {
          connection.commit();
          return DetailResult.gated(gate);
        }
        Optional<RequestHeader> request = findRequest(connection, reviewerId, requestId);
        if (request.isEmpty()) {
          connection.commit();
          return DetailResult.notFound();
        }
        RequestHeader header = request.orElseThrow();
        List<ScopePreview> scopes = readScopePreviews(connection, requestId);
        List<Claim> claims = readClaims(connection, requestId);
        List<ReviewEvent> events = readEvents(connection, requestId);
        ReviewDetail detail =
            new ReviewDetail(
                header.id(),
                header.targetChapterId(),
                header.requestVersion(),
                header.submittedAt(),
                header.policyVersion(),
                header.currentState(),
                HexFormat.of().formatHex(header.snapshotDigest()),
                scopes,
                claims,
                events);
        String etag =
            PublicationReviewEtags.decision(
                header.id(),
                header.requestVersion(),
                header.eventSequence(),
                header.currentState(),
                HexFormat.of().formatHex(header.snapshotDigest()),
                header.currentContentVersion(),
                header.currentPolicyVersion(),
                header.currentPolicySha256());
        connection.commit();
        return DetailResult.available(detail, etag);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load publication review request", exception);
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

  private static boolean hasActiveReviewerRole(Connection connection, UUID reviewerId)
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
                 FOR SHARE OF assignment, reviewer
                """)) {
      select.setObject(1, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private List<QueueItem> readQueue(
      Connection connection, UUID reviewerId, LibraryCursorCodec.Position position, int fetchLimit)
      throws SQLException {
    String cursorPredicate =
        position == null
            ? ""
            : " AND (request.submitted_at < ? OR"
                + " (request.submitted_at = ? AND request.id < ?))";
    String sql =
        """
            SELECT request.id, request.target_chapter_id,
                   request.request_version, request.submitted_at,
                   request.policy_version, encode(request.snapshot_sha256, 'hex') AS snapshot_sha256,
                   metadata.scope_snapshot #>> '{work,title}' AS work_title,
                   metadata.scope_snapshot #>> '{work,author}' AS work_author,
                   metadata.scope_snapshot #>> '{work,sourceLanguage}' AS source_language,
                   (SELECT jsonb_agg(scope.scope_code ORDER BY
                           CASE scope.scope_code WHEN 'PUBLIC_METADATA' THEN 0
                                WHEN 'CHAPTER_TEXT' THEN 1
                                WHEN 'COMMERCIAL_SALE' THEN 2
                                WHEN 'FREE_PREVIEW' THEN 3 ELSE 4 END)::text
                      FROM publication_request_scope_snapshots scope
                     WHERE scope.request_id = request.id) AS scope_codes
              FROM publication_requests request
              JOIN publication_request_current_state state
                ON state.request_id = request.id AND state.current_state = 'PENDING_REVIEW'
              JOIN works work
                ON work.id = request.work_id AND work.owner_user_id = request.owner_user_id
              JOIN users owner
                ON owner.id = request.owner_user_id AND owner.status = 'active'
              JOIN libraries library
                ON library.id = work.library_id AND library.owner_user_id = work.owner_user_id
              JOIN authoring_manuscript_creations creation
                ON creation.work_id = work.id AND creation.actor_user_id = work.owner_user_id
              JOIN publication_request_scope_snapshots metadata
                ON metadata.request_id = request.id AND metadata.scope_code = 'PUBLIC_METADATA'
             WHERE request.owner_user_id <> ?
               AND work.source_kind = 'authored'
               AND ((request.target_chapter_id IS NULL AND work.visibility = 'private')
                 OR (request.target_chapter_id IS NOT NULL AND work.visibility = 'public'))
               AND work.status = 'active' AND work.deleted_at IS NULL
               AND library.workspace_id IS NULL AND library.deleted_at IS NULL
            """
            + cursorPredicate
            + " ORDER BY request.submitted_at DESC, request.id DESC LIMIT ?";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      int index = 1;
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
                  rows.getObject("target_chapter_id", UUID.class),
                  rows.getInt("request_version"),
                  rows.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                  rows.getString("policy_version"),
                  rows.getString("snapshot_sha256"),
                  rows.getString("work_title"),
                  rows.getString("work_author"),
                  rows.getString("source_language"),
                  readStringArray(rows.getString("scope_codes"))));
        }
        return List.copyOf(items);
      }
    }
  }

  private Optional<RequestHeader> findRequest(
      Connection connection, UUID reviewerId, UUID requestId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request.id, request.target_chapter_id,
                       request.request_version, request.submitted_at,
                       request.policy_version, request.snapshot_sha256, state.current_state,
                       state.event_sequence, work.publication_content_version,
                       current_policy.version AS current_policy_version,
                       current_policy.policy_sha256 AS current_policy_sha256
                  FROM publication_requests request
                  JOIN publication_request_current_state state ON state.request_id = request.id
                  JOIN works work
                    ON work.id = request.work_id AND work.owner_user_id = request.owner_user_id
                  JOIN users owner
                    ON owner.id = request.owner_user_id AND owner.status = 'active'
                  JOIN libraries library
                    ON library.id = work.library_id AND library.owner_user_id = work.owner_user_id
                  JOIN authoring_manuscript_creations creation
                    ON creation.work_id = work.id AND creation.actor_user_id = work.owner_user_id
                  LEFT JOIN LATERAL (
                       SELECT document.version, encode(document.policy_sha256, 'hex') AS policy_sha256
                         FROM publication_rights_policy_documents document
                        WHERE document.status = 'APPROVED'
                          AND document.effective_at <= clock_timestamp()
                        ORDER BY document.effective_at DESC, document.version DESC
                        LIMIT 1
                  ) current_policy ON TRUE
                 WHERE request.id = ? AND request.owner_user_id <> ?
                   AND work.source_kind = 'authored'
                   AND ((request.target_chapter_id IS NULL AND work.visibility = 'private')
                     OR (request.target_chapter_id IS NOT NULL AND work.visibility = 'public'))
                   AND work.status = 'active' AND work.deleted_at IS NULL
                   AND library.workspace_id IS NULL AND library.deleted_at IS NULL
                """)) {
      select.setObject(1, requestId);
      select.setObject(2, reviewerId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new RequestHeader(
                row.getObject("id", UUID.class),
                row.getObject("target_chapter_id", UUID.class),
                row.getInt("request_version"),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                row.getString("policy_version"),
                row.getBytes("snapshot_sha256"),
                row.getString("current_state"),
                row.getInt("event_sequence"),
                row.getLong("publication_content_version"),
                row.getString("current_policy_version"),
                row.getString("current_policy_sha256")));
      }
    }
  }

  private List<ScopePreview> readScopePreviews(Connection connection, UUID requestId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT scope_code, scope_snapshot::text AS scope_snapshot,
                       encode(snapshot_sha256, 'hex') AS snapshot_sha256
                  FROM publication_request_scope_snapshots
                 WHERE request_id = ?
                 ORDER BY CASE scope_code WHEN 'PUBLIC_METADATA' THEN 0
                      WHEN 'CHAPTER_TEXT' THEN 1 WHEN 'COMMERCIAL_SALE' THEN 2
                      WHEN 'FREE_PREVIEW' THEN 3 ELSE 4 END
                """)) {
      select.setObject(1, requestId);
      try (ResultSet rows = select.executeQuery()) {
        List<ScopePreview> previews = new ArrayList<>();
        while (rows.next()) {
          previews.add(
              safePreview(
                  rows.getString("scope_code"),
                  rows.getString("snapshot_sha256"),
                  rows.getString("scope_snapshot")));
        }
        return List.copyOf(previews);
      }
    }
  }

  private ScopePreview safePreview(String scopeCode, String digest, String snapshotJson) {
    try {
      JsonNode root = objectMapper.readTree(snapshotJson);
      JsonNode work = root.path("work");
      JsonNode chapters = root.path("chapters");
      if (!root.isObject() || !work.isObject() || !chapters.isArray()) {
        throw new IllegalStateException("Stored publication snapshot has an invalid structure");
      }
      List<ChapterPreview> chapterPreviews = new ArrayList<>();
      for (JsonNode chapter : chapters) {
        JsonNode segments = chapter.get("segments");
        chapterPreviews.add(
            new ChapterPreview(
                UUID.fromString(textValue(chapter, "id")),
                integerValue(chapter, "ordinal"),
                textValue(chapter, "title"),
                textValue(chapter, "sourceLanguage"),
                nullableTextValue(chapter, "contentHash"),
                segments != null && segments.isArray() ? segments.size() : null));
      }
      String previewPolicyVersion = null;
      Integer previewPercent = null;
      String measurement = null;
      if ("FREE_PREVIEW".equals(scopeCode)) {
        previewPolicyVersion = textValue(root, "previewPolicyVersion");
        previewPercent = integerValue(root, "previewPercent");
        measurement = textValue(root, "measurement");
        if (!"free-preview-v1".equals(previewPolicyVersion)
            || previewPercent != 10
            || !"UNICODE_CODE_POINTS".equals(measurement)) {
          throw new IllegalStateException("Stored free-preview policy snapshot is unsupported");
        }
      }
      return new ScopePreview(
          scopeCode,
          digest,
          previewPolicyVersion,
          previewPercent,
          measurement,
          new WorkPreview(
              textValue(work, "title"),
              textValue(work, "author"),
              textValue(work, "sourceLanguage")),
          List.copyOf(chapterPreviews));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored publication snapshot could not be read", exception);
    }
  }

  private static int integerValue(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalStateException("Stored publication snapshot is missing a numeric field");
    }
    return value.intValue();
  }

  private static String textValue(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException("Stored publication snapshot is missing a text field");
    }
    return value.textValue();
  }

  private static String nullableTextValue(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) {
      throw new IllegalStateException("Stored publication snapshot has an invalid text field");
    }
    return value.textValue();
  }

  private List<Claim> readClaims(Connection connection, UUID requestId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT scope_code, rights_basis_code FROM publication_request_scope_claims"
                + " WHERE request_id = ? ORDER BY CASE scope_code WHEN 'PUBLIC_METADATA' THEN 0"
                + " WHEN 'CHAPTER_TEXT' THEN 1 WHEN 'COMMERCIAL_SALE' THEN 2"
                + " WHEN 'FREE_PREVIEW' THEN 3 WHEN 'TRANSLATION_ADAPTATION' THEN 4 ELSE 5 END")) {
      select.setObject(1, requestId);
      try (ResultSet rows = select.executeQuery()) {
        List<Claim> claims = new ArrayList<>();
        while (rows.next()) {
          claims.add(new Claim(rows.getString("scope_code"), rows.getString("rights_basis_code")));
        }
        return List.copyOf(claims);
      }
    }
  }

  private static List<ReviewEvent> readEvents(Connection connection, UUID requestId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event_sequence, actor_role_code, action_code, from_state, to_state,
                       reason_code, created_at
                  FROM publication_review_events
                 WHERE request_id = ?
                 ORDER BY event_sequence ASC
                """)) {
      select.setObject(1, requestId);
      try (ResultSet rows = select.executeQuery()) {
        List<ReviewEvent> events = new ArrayList<>();
        while (rows.next()) {
          events.add(
              new ReviewEvent(
                  rows.getInt("event_sequence"),
                  rows.getString("actor_role_code"),
                  rows.getString("action_code"),
                  rows.getString("from_state"),
                  rows.getString("to_state"),
                  rows.getString("reason_code"),
                  rows.getObject("created_at", OffsetDateTime.class).toInstant()));
        }
        return List.copyOf(events);
      }
    }
  }

  private List<String> readStringArray(String json) {
    try {
      JsonNode values = objectMapper.readTree(json);
      if (values == null || !values.isArray()) {
        throw new IllegalStateException("Stored publication scope list is invalid");
      }
      List<String> result = new ArrayList<>();
      for (JsonNode value : values) {
        if (!value.isTextual())
          throw new IllegalStateException("Stored publication scope is invalid");
        result.add(value.textValue());
      }
      return List.copyOf(result);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored publication scope list could not be read", exception);
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record RequestHeader(
      UUID id,
      UUID targetChapterId,
      int requestVersion,
      Instant submittedAt,
      String policyVersion,
      byte[] snapshotDigest,
      String currentState,
      int eventSequence,
      long currentContentVersion,
      String currentPolicyVersion,
      String currentPolicySha256) {
    private RequestHeader {
      snapshotDigest = snapshotDigest.clone();
    }

    @Override
    public byte[] snapshotDigest() {
      return snapshotDigest.clone();
    }
  }

  private enum Gate {
    AVAILABLE,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED
  }

  public record QueueItem(
      UUID requestId,
      UUID targetChapterId,
      int requestVersion,
      Instant submittedAt,
      String policyVersion,
      String snapshotSha256,
      String title,
      String author,
      String sourceLanguage,
      List<String> scopeCodes) {
    public QueueItem {
      scopeCodes = List.copyOf(scopeCodes);
    }
  }

  public record QueuePage(List<QueueItem> items, String nextCursor) {
    public QueuePage {
      items = List.copyOf(items);
    }
  }

  public record WorkPreview(String title, String author, String sourceLanguage) {}

  public record ChapterPreview(
      UUID chapterId,
      int ordinal,
      String title,
      String sourceLanguage,
      String contentHash,
      Integer segmentCount) {}

  public record ScopePreview(
      String scopeCode,
      String snapshotSha256,
      String previewPolicyVersion,
      Integer previewPercent,
      String measurement,
      WorkPreview work,
      List<ChapterPreview> chapters) {
    public ScopePreview {
      chapters = List.copyOf(chapters);
    }
  }

  public record Claim(String scopeCode, String rightsBasisCode) {}

  public record ReviewEvent(
      int sequence,
      String actorRoleCode,
      String actionCode,
      String fromState,
      String toState,
      String reasonCode,
      Instant createdAt) {}

  public record ReviewDetail(
      UUID requestId,
      UUID targetChapterId,
      int requestVersion,
      Instant submittedAt,
      String policyVersion,
      String state,
      String snapshotSha256,
      List<ScopePreview> scopes,
      List<Claim> claims,
      List<ReviewEvent> events) {
    public ReviewDetail {
      scopes = List.copyOf(scopes);
      claims = List.copyOf(claims);
      events = List.copyOf(events);
    }
  }

  public record QueueResult(Outcome outcome, QueuePage page) {
    static QueueResult available(QueuePage page) {
      return new QueueResult(Outcome.AVAILABLE, page);
    }

    static QueueResult gated(Gate gate) {
      return new QueueResult(PublicationReviewQueryService.outcome(gate), null);
    }

    static QueueResult invalidLimit() {
      return new QueueResult(Outcome.INVALID_LIMIT, null);
    }

    static QueueResult invalidCursor() {
      return new QueueResult(Outcome.INVALID_CURSOR, null);
    }

    static QueueResult cursorUnavailable() {
      return new QueueResult(Outcome.CURSOR_UNAVAILABLE, null);
    }
  }

  public record DetailResult(Outcome outcome, ReviewDetail detail, String etag) {
    static DetailResult available(ReviewDetail detail, String etag) {
      return new DetailResult(Outcome.AVAILABLE, detail, etag);
    }

    static DetailResult gated(Gate gate) {
      return new DetailResult(PublicationReviewQueryService.outcome(gate), null, null);
    }

    static DetailResult notFound() {
      return new DetailResult(Outcome.NOT_FOUND, null, null);
    }
  }

  private static Outcome outcome(Gate gate) {
    return switch (gate) {
      case AVAILABLE -> Outcome.AVAILABLE;
      case REVIEWER_REQUIRED -> Outcome.REVIEWER_REQUIRED;
      case POLICY_REQUIRED -> Outcome.POLICY_REQUIRED;
      case POLICY_NOT_CONFIGURED -> Outcome.POLICY_NOT_CONFIGURED;
    };
  }

  public enum Outcome {
    AVAILABLE,
    REVIEWER_REQUIRED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    INVALID_LIMIT,
    INVALID_CURSOR,
    CURSOR_UNAVAILABLE,
    NOT_FOUND
  }
}
