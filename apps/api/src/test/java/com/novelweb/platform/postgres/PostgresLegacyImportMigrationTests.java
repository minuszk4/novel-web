package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Verifies the forward upgrade from the deployed 55-change import staging schema. */
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresLegacyImportMigrationTests {

  private static final int LEGACY_CHANGESET_COUNT = 55;

  private static final int CURRENT_CHANGESET_COUNT = 193;

  private static final int UPGRADE_CHANGESET_COUNT =
      CURRENT_CHANGESET_COUNT - LEGACY_CHANGESET_COUNT;

  private static final List<String> LEGACY_IMPORT_STATUSES =
      List.of("upload_pending", "uploaded", "failed", "cancelled", "expired");

  @Test
  void forwardUpgradePreservesEveryLegacyImportJobState() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    String statementVersion = "migration-compat-" + UUID.randomUUID();
    Map<String, UUID> jobIds = new LinkedHashMap<>();

    try (Connection liquibaseConnection = databaseConnection();
        Connection fixtureConnection = databaseConnection()) {
      var database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(liquibaseConnection));
      var resources =
          new ClassLoaderResourceAccessor(Thread.currentThread().getContextClassLoader());
      var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", resources, database);

      liquibase.update(new Contexts(), new LabelExpression());
      assertEquals(CURRENT_CHANGESET_COUNT, changesetCount(fixtureConnection));
      insertLegacyFixture(fixtureConnection, userId, libraryId, statementVersion, jobIds);

      boolean migrationMutationStarted = false;
      try {
        migrationMutationStarted = true;
        liquibase.rollback(UPGRADE_CHANGESET_COUNT, (String) null);

        assertEquals(LEGACY_CHANGESET_COUNT, changesetCount(fixtureConnection));
        assertTrue(tableExists(fixtureConnection, "import_jobs"));
        assertTrue(!tableExists(fixtureConnection, "import_parse_jobs"));
        assertFalse(tableExists(fixtureConnection, "reader_annotations"));
        assertFalse(tableExists(fixtureConnection, "credit_top_up_orders"));
        assertFalse(tableExists(fixtureConnection, "content_entitlements"));
        assertFalse(tableExists(fixtureConnection, "vnpay_test_ipn_events"));
        assertFalse(tableExists(fixtureConnection, "momo_test_reconciliation_events"));
        assertFalse(tableExists(fixtureConnection, "vnpay_test_reconciliation_events"));
        assertFalse(tableExists(fixtureConnection, "public_catalog_rate_limits"));
        assertFalse(tableExists(fixtureConnection, "publication_rights_policy_documents"));
        assertFalse(tableExists(fixtureConnection, "publication_rights_policy_acceptances"));
        assertFalse(tableExists(fixtureConnection, "publication_request_rate_limits"));
        assertFalse(tableExists(fixtureConnection, "publication_takedown_purge_requests"));
        assertFalse(tableExists(fixtureConnection, "publication_takedown_purge_attempts"));
        assertFalse(tableExists(fixtureConnection, "translation_usage_ledger"));
        assertFalse(tableExists(fixtureConnection, "translation_candidate_review_submissions"));
        assertFalse(
            tableExists(fixtureConnection, "translation_candidate_review_submission_segments"));
        assertFalse(
            tableExists(fixtureConnection, "publication_rights_policy_acceptance_idempotency"));
        assertFalse(tableExists(fixtureConnection, "publication_scope_access_approvals"));
        assertFalse(tableExists(fixtureConnection, "publication_scope_access_revocations"));
        assertFalse(columnExists(fixtureConnection, "works", "publication_content_version"));
        assertImportJobRowsPreserved(fixtureConnection, jobIds);

        liquibase.update(new Contexts(), new LabelExpression());
        migrationMutationStarted = false;

        assertEquals(CURRENT_CHANGESET_COUNT, changesetCount(fixtureConnection));
        assertTrue(tableExists(fixtureConnection, "reader_annotations"));
        assertTrue(tableExists(fixtureConnection, "import_actor_rate_limits"));
        assertTrue(tableExists(fixtureConnection, "import_parse_jobs"));
        assertTrue(tableExists(fixtureConnection, "import_commits"));
        assertTrue(tableExists(fixtureConnection, "credit_top_up_orders"));
        assertTrue(tableExists(fixtureConnection, "momo_test_ipn_events"));
        assertTrue(tableExists(fixtureConnection, "momo_test_reconciliation_events"));
        assertTrue(tableExists(fixtureConnection, "vnpay_test_ipn_events"));
        assertTrue(tableExists(fixtureConnection, "vnpay_test_reconciliation_events"));
        assertTrue(tableExists(fixtureConnection, "publication_rights_policy_documents"));
        assertTrue(tableExists(fixtureConnection, "publication_rights_policy_acceptances"));
        assertTrue(tableExists(fixtureConnection, "publication_request_rate_limits"));
        assertTrue(tableExists(fixtureConnection, "publication_takedown_purge_requests"));
        assertTrue(tableExists(fixtureConnection, "publication_takedown_purge_attempts"));
        assertTrue(tableExists(fixtureConnection, "translation_usage_ledger"));
        assertTrue(tableExists(fixtureConnection, "translation_candidate_review_submissions"));
        assertTrue(
            tableExists(fixtureConnection, "translation_candidate_review_submission_segments"));
        assertTrue(
            tableExists(fixtureConnection, "publication_rights_policy_acceptance_idempotency"));
        assertTrue(tableExists(fixtureConnection, "publication_scope_access_approvals"));
        assertTrue(tableExists(fixtureConnection, "publication_scope_access_revocations"));
        assertTrue(columnExists(fixtureConnection, "works", "publication_content_version"));
        assertTrue(
            columnExists(
                fixtureConnection,
                "publication_scope_access_approvals",
                "approved_content_version"));
        assertTrue(columnExists(fixtureConnection, "import_jobs", "staging_file_deleted_at"));
        assertImportJobRowsPreserved(fixtureConnection, jobIds);
      } finally {
        if (migrationMutationStarted) {
          liquibase.update(new Contexts(), new LabelExpression());
        }
        deleteLegacyFixture(fixtureConnection, userId, libraryId, statementVersion);
      }
    }
  }

  private static Connection databaseConnection() throws SQLException {
    return DriverManager.getConnection(
        requiredEnvironment("DATABASE_URL"),
        requiredEnvironment("DATABASE_USERNAME"),
        requiredEnvironment("DATABASE_PASSWORD"));
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Required PostgreSQL integration variable is missing: " + name);
    }
    return value;
  }

  private static void insertLegacyFixture(
      Connection connection,
      UUID userId,
      UUID libraryId,
      String statementVersion,
      Map<String, UUID> jobIds)
      throws SQLException {
    try (PreparedStatement insertUser =
        connection.prepareStatement("INSERT INTO users (id, display_name) VALUES (?, ?)")) {
      insertUser.setObject(1, userId);
      insertUser.setString(2, "Migration compatibility fixture");
      insertUser.executeUpdate();
    }

    try (PreparedStatement insertLibrary =
        connection.prepareStatement(
            "INSERT INTO libraries (id, owner_user_id, name, is_default) VALUES (?, ?, ?, TRUE)")) {
      insertLibrary.setObject(1, libraryId);
      insertLibrary.setObject(2, userId);
      insertLibrary.setString(3, "Migration compatibility fixture");
      insertLibrary.executeUpdate();
    }

    try (PreparedStatement insertStatement =
        connection.prepareStatement(
            "INSERT INTO import_rights_statements (version, effective_at, display_text) "
                + "VALUES (?, clock_timestamp(), ?)")) {
      insertStatement.setString(1, statementVersion);
      insertStatement.setString(2, "Synthetic migration compatibility fixture only.");
      insertStatement.executeUpdate();
    }

    for (String status : LEGACY_IMPORT_STATUSES) {
      UUID acceptanceId = UUID.randomUUID();
      try (PreparedStatement insertAcceptance =
          connection.prepareStatement(
              "INSERT INTO import_rights_acceptances "
                  + "(id, user_id, idempotency_key, statement_version, statement_sha256, source) "
                  + "VALUES (?, ?, ?, ?, ?, 'web-import-flow')")) {
        insertAcceptance.setObject(1, acceptanceId);
        insertAcceptance.setObject(2, userId);
        insertAcceptance.setObject(3, UUID.randomUUID());
        insertAcceptance.setString(4, statementVersion);
        insertAcceptance.setBytes(5, new byte[32]);
        insertAcceptance.executeUpdate();
      }

      UUID jobId = UUID.randomUUID();
      insertImportJob(connection, userId, libraryId, statementVersion, acceptanceId, jobId, status);
      jobIds.put(status, jobId);
    }
  }

  private static void insertImportJob(
      Connection connection,
      UUID userId,
      UUID libraryId,
      String statementVersion,
      UUID acceptanceId,
      UUID jobId,
      String status)
      throws SQLException {
    boolean uploaded = "uploaded".equals(status);
    boolean pending = "upload_pending".equals(status);
    Instant now = Instant.now();

    try (PreparedStatement insertJob =
        connection.prepareStatement(
            "INSERT INTO import_jobs "
                + "(id, user_id, library_id, rights_acceptance_id, rights_statement_version, "
                + "original_filename, declared_content_type, declared_size_bytes, storage_key, "
                + "upload_token_sha256, upload_token_expires_at, status, expires_at, uploaded_at, "
                + "actual_size_bytes, content_sha256) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insertJob.setObject(1, jobId);
      insertJob.setObject(2, userId);
      insertJob.setObject(3, libraryId);
      insertJob.setObject(4, acceptanceId);
      insertJob.setString(5, statementVersion);
      insertJob.setString(6, "legacy-" + status + ".epub");
      insertJob.setString(7, "application/epub+zip");
      insertJob.setLong(8, 128);
      insertJob.setString(9, "staging/" + jobId + ".upload");
      insertJob.setBytes(10, pending ? new byte[32] : null);
      insertJob.setTimestamp(11, pending ? Timestamp.from(now.plus(10, ChronoUnit.MINUTES)) : null);
      insertJob.setString(12, status);
      insertJob.setTimestamp(13, Timestamp.from(now.plus(1, ChronoUnit.HOURS)));
      insertJob.setTimestamp(14, uploaded ? Timestamp.from(now) : null);
      insertJob.setObject(15, uploaded ? 64L : null);
      insertJob.setBytes(16, uploaded ? new byte[32] : null);
      insertJob.executeUpdate();
    }
  }

  private static void assertImportJobRowsPreserved(Connection connection, Map<String, UUID> jobIds)
      throws SQLException {
    for (Map.Entry<String, UUID> expected : jobIds.entrySet()) {
      try (PreparedStatement query =
          connection.prepareStatement(
              "SELECT status, original_filename, declared_size_bytes "
                  + "FROM import_jobs WHERE id = ?")) {
        query.setObject(1, expected.getValue());
        try (ResultSet row = query.executeQuery()) {
          assertTrue(row.next(), "missing legacy import job in state " + expected.getKey());
          assertEquals(expected.getKey(), row.getString("status"));
          assertEquals("legacy-" + expected.getKey() + ".epub", row.getString("original_filename"));
          assertEquals(128, row.getLong("declared_size_bytes"));
          assertTrue(!row.next(), "duplicate legacy import job in state " + expected.getKey());
        }
      }
    }
  }

  private static int changesetCount(Connection connection) throws SQLException {
    try (PreparedStatement query =
            connection.prepareStatement("SELECT COUNT(*) FROM databasechangelog");
        ResultSet row = query.executeQuery()) {
      assertTrue(row.next());
      return row.getInt(1);
    }
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?)"); ) {
      query.setString(1, table);
      try (ResultSet row = query.executeQuery()) {
        assertTrue(row.next());
        return row.getBoolean(1);
      }
    }
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)"); ) {
      query.setString(1, table);
      query.setString(2, column);
      try (ResultSet row = query.executeQuery()) {
        assertTrue(row.next());
        return row.getBoolean(1);
      }
    }
  }

  private static void deleteLegacyFixture(
      Connection connection, UUID userId, UUID libraryId, String statementVersion)
      throws SQLException {
    try (PreparedStatement deleteJobs =
        connection.prepareStatement("DELETE FROM import_jobs WHERE user_id = ?")) {
      deleteJobs.setObject(1, userId);
      deleteJobs.executeUpdate();
    }
    connection.setAutoCommit(false);
    try {
      try (PreparedStatement disableAuditTrigger =
          connection.prepareStatement(
              "ALTER TABLE import_rights_acceptances "
                  + "DISABLE TRIGGER trg_import_rights_acceptance_immutable")) {
        disableAuditTrigger.execute();
      }
      try (PreparedStatement deleteAcceptances =
          connection.prepareStatement("DELETE FROM import_rights_acceptances WHERE user_id = ?")) {
        deleteAcceptances.setObject(1, userId);
        deleteAcceptances.executeUpdate();
      }
      try (PreparedStatement enableAuditTrigger =
          connection.prepareStatement(
              "ALTER TABLE import_rights_acceptances "
                  + "ENABLE TRIGGER trg_import_rights_acceptance_immutable")) {
        enableAuditTrigger.execute();
      }
      connection.commit();
    } catch (SQLException exception) {
      connection.rollback();
      throw exception;
    } finally {
      connection.setAutoCommit(true);
    }
    try (PreparedStatement deleteStatement =
        connection.prepareStatement("DELETE FROM import_rights_statements WHERE version = ?")) {
      deleteStatement.setString(1, statementVersion);
      deleteStatement.executeUpdate();
    }
    try (PreparedStatement deleteLibrary =
        connection.prepareStatement("DELETE FROM libraries WHERE id = ?")) {
      deleteLibrary.setObject(1, libraryId);
      deleteLibrary.executeUpdate();
    }
    try (PreparedStatement deleteUser =
        connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
      deleteUser.setObject(1, userId);
      deleteUser.executeUpdate();
    }
  }
}
