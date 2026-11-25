package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.platform.OperationalQueueMetrics;
import com.novelweb.platform.PostgresQueueMetricsSnapshotReader;
import com.novelweb.platform.QueueMetricsSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "novel.auth.google.rate-limit-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.auth.google.firebase-api-key=firebase-test-key-integration-api-key-not-a-credential",
      "novel.auth.google.firebase-auth-domain=novel-web-integration-test.firebaseapp.com",
      "novel.auth.google.firebase-project-id=novel-web-integration-test",
      "novel.auth.google.firebase-app-id=1:123456789012:web:abcdef1234567890",
      "novel.security.csrf.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.library.cursor.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "novel.payment.momo.test.enabled=false",
      "novel.features.cultivation-enabled=false",
      "novel.features.commerce-sandbox-enabled=false",
      "novel.imports.parser.poll-delay=1h",
      "novel.observability.queue-snapshot.initial-delay=1h"
    })
@ActiveProfiles({"postgres", "observability"})
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresQueueMetricsIntegrationTests {

  private static final String CONTENT_SENTINEL = "queue-metrics-private-content-sentinel";
  private static final String ACTOR_SENTINEL = "queue-metrics-private-actor-sentinel";
  private static final String ORDER_SENTINEL = "queue-metrics-private-order-sentinel";

  @Autowired private DataSource dataSource;

  @Autowired private OperationalQueueMetrics operationalQueueMetrics;

  @LocalServerPort private int apiPort;

  @LocalManagementPort private int managementPort;

  @Test
  void aggregateSnapshotCountsQueueStatesAndKeepsMetricsOnManagementListenerOnly()
      throws Exception {
    QueueMetricsSnapshot snapshot;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertFixtures(connection);
        snapshot =
            new PostgresQueueMetricsSnapshotReader(transactionBoundDataSource(connection))
                .readSnapshot();
      } finally {
        connection.rollback();
      }
    }

    assertEquals(2, snapshot.activeImports());
    assertEquals(1, snapshot.queuedImportParses());
    assertEquals(1, snapshot.runningImportParses());
    assertBetween(snapshot.oldestQueuedImportParseSeconds(), 130, 150);
    assertEquals(2, snapshot.pendingTranslationJobs());
    assertEquals(1, snapshot.runningTranslationJobs());
    assertBetween(snapshot.oldestPendingTranslationSeconds(), 90, 120);
    assertEquals(6, snapshot.unresolvedMomoOrders());
    assertEquals(2, snapshot.dueMomoOrders());
    assertEquals(1, snapshot.reviewRequiredMomoOrders());
    assertBetween(snapshot.oldestDueMomoOrderSeconds(), 20, 45);

    operationalQueueMetrics.refresh();
    URI managementMetric =
        URI.create(
            "http://127.0.0.1:" + managementPort + "/actuator/metrics/novel.import.parse.queued");
    URI apiMetric = URI.create("http://127.0.0.1:" + apiPort + "/actuator/metrics");
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> exposed =
        client.send(
            HttpRequest.newBuilder(managementMetric).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> unavailableOnApi =
        client.send(
            HttpRequest.newBuilder(apiMetric).GET().build(), HttpResponse.BodyHandlers.ofString());

    assertEquals(200, exposed.statusCode());
    assertTrue(exposed.body().contains("measurements"));
    assertTrue(exposed.body().contains("\"value\":0.0"));
    assertFalse(exposed.body().contains(ACTOR_SENTINEL));
    assertFalse(exposed.body().contains(ORDER_SENTINEL));
    assertFalse(exposed.body().contains(CONTENT_SENTINEL));
    assertTrue(unavailableOnApi.statusCode() >= 400 && unavailableOnApi.statusCode() < 500);
  }

  private static void insertFixtures(Connection connection) throws SQLException {
    UUID userId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    String rightsStatementVersion = "queue-metrics-rights-fixture";
    execute(
        connection, "INSERT INTO users (id, display_name) VALUES (?, ?)", userId, ACTOR_SENTINEL);
    execute(
        connection,
        "INSERT INTO libraries (id, owner_user_id, name) VALUES (?, ?, ?)",
        libraryId,
        userId,
        "Queue metrics fixture library");
    execute(
        connection,
        "INSERT INTO works (id, library_id, owner_user_id, title) VALUES (?, ?, ?, ?)",
        workId,
        libraryId,
        userId,
        CONTENT_SENTINEL);
    execute(
        connection,
        "INSERT INTO chapters (id, work_id, ordinal, title, status, source_language) "
            + "VALUES (?, ?, 1, ?, 'ready', 'vi')",
        chapterId,
        workId,
        CONTENT_SENTINEL);
    execute(
        connection,
        "INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, "
            + "normalized_content, content_hash, created_by_user_id) "
            + "VALUES (?, ?, 1, 'plain_text', ?, ?, ?)",
        revisionId,
        chapterId,
        CONTENT_SENTINEL,
        new byte[32],
        userId);
    insertTranslationJob(
        connection,
        UUID.randomUUID(),
        userId,
        workId,
        chapterId,
        revisionId,
        "queued",
        110,
        "queue-metrics-queued-job-key");
    insertTranslationJob(
        connection,
        UUID.randomUUID(),
        userId,
        workId,
        chapterId,
        revisionId,
        "retry_wait",
        60,
        "queue-metrics-retry-job-key");
    insertTranslationJob(
        connection,
        UUID.randomUUID(),
        userId,
        workId,
        chapterId,
        revisionId,
        "translating",
        40,
        "queue-metrics-running-job-key");

    execute(
        connection,
        "INSERT INTO import_rights_statements (version, effective_at, display_text) "
            + "VALUES (?, clock_timestamp(), 'Synthetic test fixture only')",
        rightsStatementVersion);
    UUID queuedImportId = insertImport(connection, userId, libraryId, 140);
    UUID runningImportId = insertImport(connection, userId, libraryId, 80);
    execute(
        connection,
        "INSERT INTO import_parse_jobs (import_id, status, attempt, parser_version, created_at, "
            + "lease_expires_at) VALUES (?, 'queued', 0, 'epub3-v2', "
            + "clock_timestamp() - INTERVAL '140 seconds', NULL)",
        queuedImportId);
    execute(
        connection,
        "INSERT INTO import_parse_jobs (import_id, status, attempt, parser_version, created_at, "
            + "lease_expires_at) VALUES (?, 'parsing', 1, 'epub3-v2', "
            + "clock_timestamp() - INTERVAL '80 seconds', clock_timestamp() + INTERVAL '1 minute')",
        runningImportId);

    insertMomoOrder(connection, userId, "PENDING", 60, -30L, null, false);
    insertMomoOrder(connection, userId, "PENDING", 10, 60L, null, false);
    insertMomoOrder(connection, userId, "PENDING", 10, -60L, 30L, false);
    insertMomoOrder(connection, userId, "INITIATING", 121, null, null, false);
    insertMomoOrder(connection, userId, "INITIATING", 119, null, null, false);
    insertMomoOrder(connection, userId, "UNKNOWN", 60, -60L, null, true);
  }

  private static UUID insertImport(
      Connection connection, UUID userId, UUID libraryId, int ageSeconds) throws SQLException {
    UUID importId = UUID.randomUUID();
    UUID acceptanceId = UUID.randomUUID();
    execute(
        connection,
        "INSERT INTO import_rights_acceptances (id, user_id, idempotency_key, statement_version, "
            + "statement_sha256, source) VALUES (?, ?, ?, ?, ?, 'web-import-flow')",
        acceptanceId,
        userId,
        UUID.randomUUID(),
        "queue-metrics-rights-fixture",
        new byte[32]);
    execute(
        connection,
        "INSERT INTO import_jobs (id, user_id, library_id, rights_acceptance_id, "
            + "rights_statement_version, original_filename, declared_content_type, "
            + "declared_size_bytes, storage_key, status, created_at, expires_at, uploaded_at, "
            + "actual_size_bytes, content_sha256) VALUES (?, ?, ?, ?, ?, 'fixture.txt', "
            + "'text/plain', 1, ?, 'uploaded', clock_timestamp() - (? * INTERVAL '1 second'), "
            + "clock_timestamp() + INTERVAL '1 hour', clock_timestamp(), 1, ?)",
        importId,
        userId,
        libraryId,
        acceptanceId,
        "queue-metrics-rights-fixture",
        "staging/" + importId + ".upload",
        ageSeconds,
        new byte[32]);
    return importId;
  }

  private static void insertTranslationJob(
      Connection connection,
      UUID jobId,
      UUID userId,
      UUID workId,
      UUID chapterId,
      UUID revisionId,
      String status,
      int ageSeconds,
      String idempotencyKey)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO translation_jobs (id, requested_by_user_id, work_id, chapter_id, "
            + "source_revision_id, source_language, target_language, provider_id, profile_snapshot, "
            + "source_sha256, request_sha256, idempotency_key, status, total_units, created_at) "
            + "VALUES (?, ?, ?, ?, ?, 'vi', 'en', 'fake-local', '{}'::jsonb, ?, ?, ?, ?, 1, "
            + "clock_timestamp() - (? * INTERVAL '1 second'))",
        jobId,
        userId,
        workId,
        chapterId,
        revisionId,
        new byte[32],
        new byte[32],
        idempotencyKey,
        status,
        ageSeconds);
  }

  private static void insertMomoOrder(
      Connection connection,
      UUID userId,
      String status,
      int ageSeconds,
      Long nextDelaySeconds,
      Long leaseDelaySeconds,
      boolean reviewRequired)
      throws SQLException {
    String nextAt =
        nextDelaySeconds == null
            ? "NULL"
            : "clock_timestamp() + (" + nextDelaySeconds + " * INTERVAL '1 second')";
    String leaseUntil =
        leaseDelaySeconds == null
            ? "NULL"
            : "clock_timestamp() + (" + leaseDelaySeconds + " * INTERVAL '1 second')";
    String reviewAt = reviewRequired ? "clock_timestamp() - INTERVAL '30 seconds'" : "NULL";
    String reviewCode = reviewRequired ? "'QUERY_INVALID_RESPONSE'" : "NULL";
    execute(
        connection,
        "INSERT INTO credit_top_up_orders (id, user_id, request_id, idempotency_key, pack_id, "
            + "pack_version, provider, environment, currency, amount_vnd, paid_credits, status, "
            + "updated_at, next_reconciliation_at, reconciliation_lease_until, "
            + "reconciliation_review_required_at, reconciliation_review_code) "
            + "VALUES (?, ?, ?, ?, 'momo-test-credits-100', 1, 'MOMO', 'TEST', 'VND', 1000, 100, ?, "
            + "clock_timestamp() - ( ? * INTERVAL '1 second'), "
            + nextAt
            + ", "
            + leaseUntil
            + ", "
            + reviewAt
            + ", "
            + reviewCode
            + ")",
        UUID.randomUUID(),
        userId,
        UUID.randomUUID(),
        "queue-metrics-order-" + UUID.randomUUID(),
        status,
        ageSeconds);
  }

  private static void execute(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      statement.executeUpdate();
    }
  }

  private static DataSource transactionBoundDataSource(Connection transactionConnection) {
    Connection nonClosingConnection =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("close")) return null;
                  try {
                    return method.invoke(transactionConnection, arguments);
                  } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                  }
                });
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getConnection")) return nonClosingConnection;
              throw new UnsupportedOperationException("Only getConnection is supported");
            });
  }

  private static void assertBetween(long actual, long minimum, long maximum) {
    assertTrue(
        actual >= minimum && actual <= maximum,
        actual + " not in [" + minimum + ", " + maximum + "]");
  }
}
