package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.library.PublicCatalogRateLimiter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "novel.features.public-catalog-enabled=true",
      "novel.public-catalog.rate-limit.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.public-catalog.trusted-proxies=127.0.0.1/32",
      "novel.public-catalog.rate-limit.capacity=60",
      "novel.public-catalog.rate-limit.refill-interval=1h",
      "novel.library.cursor.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.auth.google.rate-limit-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.auth.google.web-client-id=catalog-test.apps.googleusercontent.com",
      "novel.auth.google.credential-callback-uri=https://api.example.test/api/v1/auth/google/credential",
      "novel.security.csrf.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.payment.momo.test.enabled=false",
      "novel.features.cultivation-enabled=false",
      "novel.features.commerce-sandbox-enabled=false",
      "novel.imports.parser.poll-delay=1h"
    })
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresPublicCatalogIntegrationTests {

  private final List<UUID> workIds = new ArrayList<>();
  private UUID userId;
  private UUID libraryId;
  private UUID reviewerId;
  private String publicationPolicyVersion;
  private byte[] publicationPolicySha256;

  @Autowired private DataSource dataSource;

  @Autowired private PublicCatalogRateLimiter publicCatalogRateLimiter;

  @Autowired private ObjectMapper objectMapper;

  @LocalServerPort private int apiPort;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @BeforeEach
  void createSyntheticOwnerAndPrivateLibrary() throws Exception {
    userId = UUID.randomUUID();
    libraryId = UUID.randomUUID();
    reviewerId = UUID.randomUUID();
    publicationPolicyVersion = "catalog-test-" + UUID.randomUUID().toString().replace("-", "");
    byte[] publicationPolicyText =
        "Synthetic catalog integration policy; no legal text is represented."
            .getBytes(StandardCharsets.UTF_8);
    publicationPolicySha256 = sha256(publicationPolicyText);
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "INSERT INTO users (id, display_name) VALUES (?, ?)",
          userId,
          "Synthetic catalog owner");
      execute(
          connection,
          "INSERT INTO libraries (id, owner_user_id, name, is_default) VALUES (?, ?, ?, TRUE)",
          libraryId,
          userId,
          "Synthetic private catalog fixture");
      execute(
          connection,
          "INSERT INTO users (id, display_name) VALUES (?, ?)",
          reviewerId,
          "Synthetic catalog reviewer");
      execute(
          connection,
          """
              INSERT INTO security_role_assignments (
                  id, user_id, role_code, assigned_by_user_id, reason_code)
              VALUES (?, ?, 'publication_reviewer', ?, 'REVIEW_CAPACITY')
              """,
          UUID.randomUUID(),
          reviewerId,
          userId);
      execute(
          connection,
          """
              INSERT INTO publication_rights_policy_documents (
                  version, effective_at, display_text, policy_sha256)
              VALUES (?, clock_timestamp() - INTERVAL '1 second', ?, ?)
              """,
          publicationPolicyVersion,
          new String(publicationPolicyText, StandardCharsets.UTF_8),
          publicationPolicySha256);
      execute(
          connection,
          "UPDATE publication_rights_policy_documents SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = ? WHERE version = ?",
          reviewerId,
          publicationPolicyVersion);
    }
  }

  @AfterEach
  void removeSyntheticFixtures() throws Exception {
    for (UUID workId : workIds) deleteWork(workId);
    workIds.clear();
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, "DELETE FROM public_catalog_rate_limits");
      if (libraryId != null) {
        execute(connection, "DELETE FROM security_role_assignments WHERE user_id = ?", reviewerId);
        try (java.sql.Statement disable = connection.createStatement()) {
          disable.execute(
              "ALTER TABLE publication_rights_policy_documents"
                  + " DISABLE TRIGGER trg_publication_rights_policy_immutable");
        }
        execute(
            connection,
            "DELETE FROM publication_rights_policy_documents WHERE version = ?",
            publicationPolicyVersion);
        try (java.sql.Statement enable = connection.createStatement()) {
          enable.execute(
              "ALTER TABLE publication_rights_policy_documents"
                  + " ENABLE TRIGGER trg_publication_rights_policy_immutable");
        }
        execute(connection, "DELETE FROM users WHERE id = ?", reviewerId);
        execute(connection, "DELETE FROM libraries WHERE id = ?", libraryId);
        execute(connection, "DELETE FROM users WHERE id = ?", userId);
      }
    }
  }

  @Test
  void anonymousCatalogReturnsOnlyActivePublicAuthoredWorksWithReadyChapters() throws Exception {
    UUID listed =
        createWork("Public authored", "Public author", "vi", "authored", "public", "active", true);
    createWork("Private authored", "Private author", "vi", "authored", "private", "active", true);
    createWork(
        "Unlisted authored", "Unlisted author", "vi", "authored", "unlisted", "active", true);
    createWork("Imported public", "Imported author", "vi", "imported", "public", "active", true);
    createWork("Archived public", "Archived author", "vi", "authored", "public", "archived", true);
    createWork("Draft public", "Draft author", "vi", "authored", "public", "active", false);
    UUID deleted =
        createWork("Deleted public", "Deleted author", "vi", "authored", "public", "active", true);
    setDeletedAt(deleted);

    HttpResult result = get("/api/v1/catalog/works?limit=50");

    assertEquals(200, result.statusCode());
    assertTrue(result.cacheControl().contains("no-store"));
    assertEquals(1, result.body().path("items").size());
    JsonNode item = result.body().path("items").get(0);
    assertEquals(listed.toString(), item.path("id").asText());
    assertEquals("Public authored", item.path("title").asText());
    assertEquals("Public author", item.path("author").asText());
    assertEquals("vi", item.path("sourceLanguage").asText());
    assertEquals(1, item.path("readyChapterCount").asInt());
    assertFalse(item.has("content"));
    assertFalse(item.has("description"));
    assertFalse(item.has("createdAt"));
    assertTrue(result.body().path("nextCursor").isNull());

    setOwnerStatus("suspended");
    HttpResult inactiveOwner = get("/api/v1/catalog/works?limit=50");
    assertEquals(200, inactiveOwner.statusCode());
    assertEquals(0, inactiveOwner.body().path("items").size());
  }

  @Test
  void searchFiltersLanguageAndUsesBoundedStablePagination() throws Exception {
    Instant oldest = Instant.parse("2026-11-14T00:00:00Z");
    Instant newest = Instant.parse("2026-11-16T00:00:00Z");
    UUID percentWork =
        createWork(
            "Dragon 100%", "Search author", "vi", "authored", "public", "active", true, newest);
    UUID underscoreWork =
        createWork(
            "Dragon_underscore",
            "Search author",
            "vi",
            "authored",
            "public",
            "active",
            true,
            oldest);
    createWork("Dragon en", "Search author", "en", "authored", "public", "active", true);

    HttpResult first = get("/api/v1/catalog/works?q=Dragon&language=VI&limit=1");
    assertEquals(200, first.statusCode());
    assertEquals(percentWork.toString(), first.body().path("items").get(0).path("id").asText());
    String cursor = first.body().path("nextCursor").asText();
    assertFalse(cursor.isBlank());

    HttpResult second =
        get(
            "/api/v1/catalog/works?q=dragon&language=vi&limit=1&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    assertEquals(200, second.statusCode());
    assertEquals(underscoreWork.toString(), second.body().path("items").get(0).path("id").asText());
    assertTrue(second.body().path("nextCursor").isNull());

    HttpResult literalPercent = get("/api/v1/catalog/works?q=%25&language=vi&limit=5");
    assertEquals(200, literalPercent.statusCode());
    assertEquals(1, literalPercent.body().path("items").size());
    assertEquals(
        percentWork.toString(), literalPercent.body().path("items").get(0).path("id").asText());
  }

  @Test
  void cursorCannotMoveAcrossFiltersAndTakedownIsAppliedOnNextPage() throws Exception {
    Instant firstTime = Instant.parse("2026-11-13T00:00:00Z");
    Instant secondTime = Instant.parse("2026-11-14T00:00:00Z");
    Instant thirdTime = Instant.parse("2026-11-15T00:00:00Z");
    UUID firstWork =
        createWork("Saga one", "Author", "vi", "authored", "public", "active", true, firstTime);
    UUID secondWork =
        createWork("Saga two", "Author", "vi", "authored", "public", "active", true, secondTime);
    createWork("Saga three", "Author", "vi", "authored", "public", "active", true, thirdTime);

    HttpResult first = get("/api/v1/catalog/works?q=saga&limit=1");
    String cursor = first.body().path("nextCursor").asText();
    assertFalse(cursor.isBlank());

    HttpResult wrongFilter =
        get(
            "/api/v1/catalog/works?q=other&limit=1&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    assertEquals(400, wrongFilter.statusCode());
    assertEquals("INVALID_CURSOR", wrongFilter.body().path("code").asText());

    setVisibility(secondWork, "private");
    HttpResult afterTakedown =
        get(
            "/api/v1/catalog/works?q=saga&limit=1&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    assertEquals(200, afterTakedown.statusCode());
    assertEquals(
        firstWork.toString(), afterTakedown.body().path("items").get(0).path("id").asText());
    assertNotNull(afterTakedown.body().path("items").get(0));
  }

  @Test
  void rejectsMalformedLanguageOversizedQueryAndInvalidLimits() throws Exception {
    assertEquals(400, get("/api/v1/catalog/works?language=vi%21").statusCode());
    assertEquals(400, get("/api/v1/catalog/works?limit=51").statusCode());
    assertEquals(400, get("/api/v1/catalog/works?q=" + "x".repeat(121)).statusCode());
  }

  @Test
  void publicCatalogFailsClosedForMissingStaleAndRevokedScopeApproval() throws Exception {
    UUID missingApproval =
        createWork(
            "No approval",
            "Synthetic Author",
            "vi",
            "authored",
            "public",
            "active",
            true,
            Instant.now(),
            false);
    UUID staleApproval =
        createWork(
            "Stale snapshot", "Synthetic Author", "vi", "authored", "public", "active", true);
    UUID revokedApproval =
        createWork(
            "Revoked approval", "Synthetic Author", "vi", "authored", "public", "active", true);
    try (Connection connection = dataSource.getConnection()) {
      SQLException missingOwnerAcceptance =
          assertThrows(
              SQLException.class,
              () -> insertSyntheticPublicationApproval(connection, missingApproval));
      assertEquals("23514", missingOwnerAcceptance.getSQLState());
    }
    revokeSyntheticPublicationApproval(revokedApproval);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE works SET title = 'Edited after review' WHERE id = ?")) {
      update.setObject(1, staleApproval);
      assertEquals(1, update.executeUpdate());
    }

    HttpResult result = get("/api/v1/catalog/works?limit=50");

    assertEquals(200, result.statusCode());
    assertEquals(0, result.body().path("items").size());
    assertFalse(result.body().toString().contains(missingApproval.toString()));
    assertFalse(result.body().toString().contains(staleApproval.toString()));
    assertFalse(result.body().toString().contains(revokedApproval.toString()));
  }

  @Test
  void rateLimitsSearchAndCursorReadsPerTrustedClientAddress() throws Exception {
    String clientAddress = testClientAddress();
    String path = "/api/v1/catalog/works?q=rate-limit-sentinel&limit=1";

    List<Future<HttpResult>> concurrentResponses = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(24)) {
      for (int request = 0; request < 72; request++) {
        concurrentResponses.add(executor.submit(() -> get(path, clientAddress)));
      }
    }
    List<HttpResult> responses = new ArrayList<>(concurrentResponses.size());
    for (Future<HttpResult> response : concurrentResponses) responses.add(response.get());

    List<HttpResult> allowed =
        responses.stream().filter(response -> response.statusCode() == 200).toList();
    List<HttpResult> limited =
        responses.stream().filter(response -> response.statusCode() == 429).toList();
    assertEquals(60, allowed.size());
    assertEquals(12, limited.size());
    assertEquals(List.of("3600"), limited.stream().map(HttpResult::retryAfter).distinct().toList());
    assertEquals("PUBLIC_CATALOG_RATE_LIMITED", limited.get(0).body().path("code").asText());
    assertEquals("3600", limited.get(0).retryAfter());
    assertTrue(limited.get(0).cacheControl().contains("no-store"));
    assertEquals(200, get(path, testClientAddress()).statusCode());

    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT rate_limit_key_hash FROM public_catalog_rate_limits")) {
      try (var rows = select.executeQuery()) {
        java.util.Set<String> observedBuckets = new java.util.HashSet<>();
        while (rows.next()) {
          byte[] hash = rows.getBytes(1);
          assertEquals(32, hash.length);
          observedBuckets.add(java.util.HexFormat.of().formatHex(hash));
        }
        assertEquals(2, observedBuckets.size());
      }
    }
  }

  @Test
  void purgesAtMost500InactiveBucketsAndRetainsBucketsSeenWithin24Hours() throws Exception {
    byte[] activeHash = newRateLimitHash();
    byte[] recentHash = newRateLimitHash();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insertStale =
            connection.prepareStatement(
                """
                    INSERT INTO public_catalog_rate_limits (
                        rate_limit_key_hash, available_tokens, refilled_at, last_seen_at)
                    VALUES (?, 0, clock_timestamp(), clock_timestamp() - INTERVAL '25 hours')
                    """)) {
      for (int index = 0; index < 502; index++) {
        byte[] staleHash = newRateLimitHash();
        insertStale.setBytes(1, staleHash);
        insertStale.addBatch();
      }
      int[] inserted = insertStale.executeBatch();
      assertEquals(502, inserted.length);
      assertTrue(java.util.Arrays.stream(inserted).allMatch(rows -> rows == 1));
    }
    insertRateLimitBucket(activeHash, 0);
    insertRateLimitBucket(recentHash, 23);

    assertEquals(500, publicCatalogRateLimiter.purgeInactiveBuckets());
    assertEquals(2, countInactiveRateLimitBuckets());
    assertEquals(4, countRateLimitBuckets());

    assertEquals(2, publicCatalogRateLimiter.purgeInactiveBuckets());
    assertEquals(0, countInactiveRateLimitBuckets());
    assertEquals(1, rateLimitBucketCount(activeHash));
    assertEquals(1, rateLimitBucketCount(recentHash));
    assertEquals(2, countRateLimitBuckets());
  }

  @Test
  void cleanupSkipsAConcurrentActiveBucketAndPurgesOtherStaleBuckets() throws Exception {
    byte[] activeHash = newRateLimitHash();
    byte[] staleHash = newRateLimitHash();
    insertRateLimitBucket(activeHash, 25);
    insertRateLimitBucket(staleHash, 25);
    var executor = Executors.newSingleThreadExecutor();

    try (Connection activeRequest = dataSource.getConnection()) {
      activeRequest.setAutoCommit(false);
      try (PreparedStatement markActive =
          activeRequest.prepareStatement(
              """
                  UPDATE public_catalog_rate_limits
                  SET last_seen_at = clock_timestamp()
                  WHERE rate_limit_key_hash = ?
                  """)) {
        markActive.setBytes(1, activeHash);
        assertEquals(1, markActive.executeUpdate());
      }

      Future<Integer> cleanup = executor.submit(publicCatalogRateLimiter::purgeInactiveBuckets);
      int deleted;
      try {
        deleted = cleanup.get(5, TimeUnit.SECONDS);
      } finally {
        activeRequest.commit();
      }
      assertEquals(1, deleted);
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    assertEquals(1, rateLimitBucketCount(activeHash));
    assertEquals(0, rateLimitBucketCount(staleHash));
    assertEquals(0, countInactiveRateLimitBuckets());
  }

  private void insertRateLimitBucket(byte[] keyHash, int lastSeenHoursAgo) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO public_catalog_rate_limits (
                        rate_limit_key_hash, available_tokens, refilled_at, last_seen_at)
                    VALUES (?, 0, clock_timestamp(), clock_timestamp() - (? * INTERVAL '1 hour'))
                    """)) {
      insert.setBytes(1, keyHash);
      insert.setInt(2, lastSeenHoursAgo);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private int countRateLimitBuckets() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT count(*) FROM public_catalog_rate_limits");
        var row = select.executeQuery()) {
      if (!row.next()) throw new SQLException("Rate-limit bucket count returned no row");
      return row.getInt(1);
    }
  }

  private int countInactiveRateLimitBuckets() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT count(*)
                    FROM public_catalog_rate_limits
                    WHERE last_seen_at < clock_timestamp() - INTERVAL '24 hours'
                    """);
        var row = select.executeQuery()) {
      if (!row.next()) throw new SQLException("Inactive rate-limit bucket count returned no row");
      return row.getInt(1);
    }
  }

  private int rateLimitBucketCount(byte[] keyHash) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT count(*)
                    FROM public_catalog_rate_limits
                    WHERE rate_limit_key_hash = ?
                    """)) {
      select.setBytes(1, keyHash);
      try (var row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Rate-limit bucket count returned no row");
        return row.getInt(1);
      }
    }
  }

  private static byte[] newRateLimitHash() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    return ByteBuffer.allocate(32)
        .putLong(first.getMostSignificantBits())
        .putLong(first.getLeastSignificantBits())
        .putLong(second.getMostSignificantBits())
        .putLong(second.getLeastSignificantBits())
        .array();
  }

  private UUID createWork(
      String title,
      String author,
      String language,
      String sourceKind,
      String visibility,
      String status,
      boolean withReadyChapter)
      throws Exception {
    return createWork(
        title, author, language, sourceKind, visibility, status, withReadyChapter, Instant.now());
  }

  private UUID createWork(
      String title,
      String author,
      String language,
      String sourceKind,
      String visibility,
      String status,
      boolean withReadyChapter,
      Instant createdAt)
      throws Exception {
    return createWork(
        title, author, language, sourceKind, visibility, status, withReadyChapter, createdAt, true);
  }

  private UUID createWork(
      String title,
      String author,
      String language,
      String sourceKind,
      String visibility,
      String status,
      boolean withReadyChapter,
      Instant createdAt,
      boolean approvePublicMetadata)
      throws Exception {
    UUID workId = UUID.randomUUID();
    workIds.add(workId);
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement insert =
          connection.prepareStatement(
              """
                  INSERT INTO works (
                    id, library_id, owner_user_id, title, author, source_language,
                    source_kind, visibility, status, created_at, deleted_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'deleted' THEN clock_timestamp() END)
                  """)) {
        insert.setObject(1, workId);
        insert.setObject(2, libraryId);
        insert.setObject(3, userId);
        insert.setString(4, title);
        insert.setString(5, author);
        insert.setString(6, language);
        insert.setString(7, sourceKind);
        insert.setString(8, visibility);
        insert.setString(9, status);
        insert.setTimestamp(10, Timestamp.from(createdAt));
        insert.setString(11, status);
        insert.executeUpdate();
      }
      if ("authored".equals(sourceKind)) insertSyntheticAuthoredWork(connection, workId);
      if (withReadyChapter) insertReadyChapter(connection, workId, title + " Chapter");
      if (approvePublicMetadata
          && "authored".equals(sourceKind)
          && "public".equals(visibility)
          && "active".equals(status)) {
        insertSyntheticRightsAcceptance(connection, workId);
        insertSyntheticPublicationApproval(connection, workId);
      }
    }
    return workId;
  }

  private void insertSyntheticAuthoredWork(Connection connection, UUID workId) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO authoring_manuscript_creations (
                    actor_user_id, idempotency_key, request_sha256, work_id)
                VALUES (?, ?, ?, ?)
                """)) {
      insert.setObject(1, userId);
      insert.setString(2, "catalog-manuscript-" + UUID.randomUUID().toString().replace("-", ""));
      insert.setBytes(3, sha256(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
      insert.setObject(4, workId);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertSyntheticRightsAcceptance(Connection connection, UUID workId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_rights_policy_acceptances (
                    id, user_id, work_id, idempotency_key, policy_version, policy_sha256, source)
                VALUES (?, ?, ?, ?, ?, ?, 'web-authoring-publication')
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, userId);
      insert.setObject(3, workId);
      insert.setObject(4, UUID.randomUUID());
      insert.setString(5, publicationPolicyVersion);
      insert.setBytes(6, publicationPolicySha256);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertSyntheticPublicationApproval(Connection connection, UUID workId)
      throws SQLException {
    String snapshot;
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT publication_work_scope_snapshot(?, 'PUBLIC_METADATA')::text")) {
      select.setObject(1, workId);
      try (var row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Synthetic publication snapshot returned no row");
        snapshot = row.getString(1);
      }
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_scope_access_approvals (
                    id, work_id, scope_code, approved_snapshot, policy_version, policy_sha256,
                    approved_by_user_id, reason_code)
                VALUES (?, ?, 'PUBLIC_METADATA', CAST(? AS JSONB), ?, ?, ?, 'RIGHTS_REVIEWED')
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, workId);
      insert.setString(3, snapshot);
      insert.setString(4, publicationPolicyVersion);
      insert.setBytes(5, publicationPolicySha256);
      insert.setObject(6, reviewerId);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void revokeSyntheticPublicationApproval(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO publication_scope_access_revocations (
                        approval_id, revoked_by_user_id, reason_code)
                    SELECT id, ?, 'RIGHTS_WITHDRAWN'
                    FROM publication_scope_access_approvals
                    WHERE work_id = ? AND scope_code = 'PUBLIC_METADATA'
                    """)) {
      insert.setObject(1, reviewerId);
      insert.setObject(2, workId);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertReadyChapter(Connection connection, UUID workId, String title)
      throws SQLException {
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    execute(
        connection,
        "INSERT INTO chapters (id, work_id, ordinal, title, source_language) VALUES (?, ?, 1, ?, 'vi')",
        chapterId,
        workId,
        title);
    try (PreparedStatement revision =
        connection.prepareStatement(
            """
                INSERT INTO chapter_revisions (
                  id, chapter_id, revision_no, source_format, normalized_content,
                  content_hash, created_by_user_id)
                VALUES (?, ?, 1, 'plain_text', ?, ?, ?)
                """)) {
      revision.setObject(1, revisionId);
      revision.setObject(2, chapterId);
      revision.setString(3, "synthetic private test body; never included in catalog DTO");
      revision.setBytes(4, new byte[32]);
      revision.setObject(5, userId);
      revision.executeUpdate();
    }
    try (PreparedStatement ready =
        connection.prepareStatement(
            "UPDATE chapters SET source_revision_id = ?, status = 'ready' WHERE id = ?")) {
      ready.setObject(1, revisionId);
      ready.setObject(2, chapterId);
      ready.executeUpdate();
    }
  }

  private void setVisibility(UUID workId, String visibility) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE works SET visibility = ? WHERE id = ?")) {
      update.setString(1, visibility);
      update.setObject(2, workId);
      update.executeUpdate();
    }
  }

  private void setDeletedAt(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE works SET status = 'deleted', deleted_at = clock_timestamp() WHERE id = ?")) {
      update.setObject(1, workId);
      update.executeUpdate();
    }
  }

  private void setOwnerStatus(String status) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE users SET status = ? WHERE id = ?")) {
      update.setString(1, status);
      update.setObject(2, userId);
      update.executeUpdate();
    }
  }

  private void deleteWork(UUID workId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement deleteManuscript =
              connection.prepareStatement(
                  "DELETE FROM authoring_manuscript_creations WHERE work_id = ?");
          PreparedStatement deleteAcceptance =
              connection.prepareStatement(
                  "DELETE FROM publication_rights_policy_acceptances WHERE work_id = ?")) {
        try (java.sql.Statement disable = connection.createStatement()) {
          disable.execute(
              "ALTER TABLE publication_rights_policy_acceptances"
                  + " DISABLE TRIGGER trg_publication_rights_acceptance_immutable");
        }
        deleteAcceptance.setObject(1, workId);
        deleteAcceptance.executeUpdate();
        try (java.sql.Statement enable = connection.createStatement()) {
          enable.execute(
              "ALTER TABLE publication_rights_policy_acceptances"
                  + " ENABLE TRIGGER trg_publication_rights_acceptance_immutable");
        }
        deleteManuscript.setObject(1, workId);
        deleteManuscript.executeUpdate();
      }
      try (PreparedStatement clearRevision =
          connection.prepareStatement(
              "UPDATE chapters SET source_revision_id = NULL WHERE work_id = ?")) {
        clearRevision.setObject(1, workId);
        clearRevision.executeUpdate();
      }
      try (PreparedStatement revisions =
          connection.prepareStatement(
              "DELETE FROM chapter_revisions WHERE chapter_id IN (SELECT id FROM chapters WHERE work_id = ?)")) {
        revisions.setObject(1, workId);
        revisions.executeUpdate();
      }
      execute(connection, "DELETE FROM chapters WHERE work_id = ?", workId);
      execute(connection, "DELETE FROM works WHERE id = ?", workId);
    }
  }

  private HttpResult get(String path) throws Exception {
    return get(path, null);
  }

  private HttpResult get(String path, String forwardedFor) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + path)).GET();
    if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
    HttpResponse<String> response =
        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new HttpResult(
        response.statusCode(),
        response.headers().firstValue("Cache-Control").orElse(""),
        response.headers().firstValue("Retry-After").orElse(""),
        objectMapper.readTree(response.body()));
  }

  private static String testClientAddress() {
    String random = UUID.randomUUID().toString().replace("-", "");
    return "2001:db8:"
        + random.substring(0, 4)
        + ":"
        + random.substring(4, 8)
        + "::"
        + random.substring(8, 12)
        + ":"
        + random.substring(12, 16);
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private record HttpResult(
      int statusCode, String cacheControl, String retryAfter, JsonNode body) {}
}
