package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.identity.privacy.AccountDataExportArchiveWriter;
import com.novelweb.identity.privacy.AccountDataExportArchiveWriter.Artifact;
import com.novelweb.identity.privacy.AccountDataExportRepository;
import com.novelweb.identity.privacy.AccountDataExportRepository.ClaimedJob;
import com.novelweb.identity.privacy.AccountDataExportRepository.CreateOutcome;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExportStatus;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresAccountDataExportIntegrationTests {

  private static final String OWNER_SENTINEL = "PRIVATE-OWNED-CHAPTER-SENTINEL";
  private static final String FOREIGN_SENTINEL = "FOREIGN-ACCOUNT-CHAPTER-SENTINEL";

  @Autowired private DataSource dataSource;
  @Autowired private AccountDataExportRepository repository;
  @Autowired private AccountDataExportArchiveWriter archiveWriter;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void exportIsIdempotentActorScopedAndIncludesFullOwnedChapterContent() throws Exception {
    Fixture fixture = new Fixture();
    UUID exportId = null;
    UUID storageKey = UUID.randomUUID();
    try {
      insertFixture(fixture);
      byte[] idempotencyHash = new byte[32];
      idempotencyHash[0] = 1;

      var created = repository.createOrReplay(fixture.ownerId(), idempotencyHash);
      assertEquals(CreateOutcome.CREATED, created.outcome());
      exportId = created.job().id();
      var replay = repository.createOrReplay(fixture.ownerId(), idempotencyHash);
      assertEquals(CreateOutcome.REPLAYED, replay.outcome());
      assertEquals(exportId, replay.job().id());
      assertEquals(
          CreateOutcome.ACTIVE_EXPORT_EXISTS,
          repository.createOrReplay(fixture.ownerId(), new byte[32]).outcome());
      assertTrue(repository.findOwned(fixture.foreignId(), exportId).isEmpty());

      ClaimedJob claim = repository.claimNext().orElseThrow();
      assertEquals(exportId, claim.exportId());
      assertEquals(fixture.ownerId(), claim.userId());
      Artifact artifact = archiveWriter.create(claim, storageKey);
      try (ZipFile zip = new ZipFile(artifact.path().toFile(), StandardCharsets.UTF_8)) {
        String revisions = read(zip, "owned_chapter_revisions.jsonl");
        String segments = read(zip, "owned_chapter_segments.jsonl");
        String profile = read(zip, "profile.jsonl");
        String privacyPreferences = read(zip, "optional_privacy_preferences.jsonl");
        String manifest = read(zip, "manifest.json");
        List<JsonNode> privacyRecords = privacyPreferences.lines().map(this::readJson).toList();

        assertTrue(revisions.contains(OWNER_SENTINEL));
        assertTrue(segments.contains(OWNER_SENTINEL));
        assertTrue(profile.contains("Synthetic export owner"));
        assertEquals(1, privacyRecords.size());
        assertTrue(privacyRecords.getFirst().path("analyticsEnabled").asBoolean());
        assertFalse(privacyRecords.getFirst().path("contentProviderSharingEnabled").asBoolean());
        assertTrue(manifest.contains("optional_privacy_preferences"));
        assertTrue(manifest.contains("schemaVersion"));
        assertFalse(revisions.contains(FOREIGN_SENTINEL));
        assertFalse(segments.contains(FOREIGN_SENTINEL));
        assertFalse(revisions.contains(fixture.foreignId().toString()));
      }
      assertTrue(
          repository.markReady(
              claim, storageKey, artifact.sha256(), artifact.size(), Duration.ofHours(1)));
      var ready = repository.findOwned(fixture.ownerId(), exportId).orElseThrow();
      assertEquals(ExportStatus.READY, ready.status());
      assertEquals(artifact.size(), ready.artifactBytes());
      assertArrayEquals(artifact.sha256(), ready.artifactSha256());
      assertTrue(repository.findOwned(fixture.foreignId(), exportId).isEmpty());
    } finally {
      archiveWriter.delete(storageKey);
      deleteFixture(fixture);
    }
  }

  private void insertFixture(Fixture fixture) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(
            connection,
            "INSERT INTO users (id, display_name) VALUES (?, ?)",
            fixture.ownerId(),
            "Synthetic export owner");
        execute(
            connection,
            "INSERT INTO users (id, display_name) VALUES (?, ?)",
            fixture.foreignId(),
            "Synthetic foreign owner");
        execute(
            connection,
            """
                INSERT INTO account_privacy_preferences
                    (user_id, analytics_enabled, content_provider_sharing_enabled)
                VALUES (?, TRUE, FALSE)
                """,
            fixture.ownerId());
        execute(
            connection,
            """
                INSERT INTO account_privacy_preferences
                    (user_id, analytics_enabled, content_provider_sharing_enabled)
                VALUES (?, FALSE, TRUE)
                """,
            fixture.foreignId());
        execute(
            connection,
            "INSERT INTO libraries (id, owner_user_id, name, is_default) VALUES (?, ?, ?, TRUE)",
            fixture.ownerLibraryId(),
            fixture.ownerId(),
            "Synthetic owned library");
        execute(
            connection,
            "INSERT INTO libraries (id, owner_user_id, name, is_default) VALUES (?, ?, ?, TRUE)",
            fixture.foreignLibraryId(),
            fixture.foreignId(),
            "Synthetic foreign library");
        insertWork(
            connection,
            fixture.ownerWorkId(),
            fixture.ownerLibraryId(),
            fixture.ownerId(),
            "Synthetic owned story");
        insertWork(
            connection,
            fixture.foreignWorkId(),
            fixture.foreignLibraryId(),
            fixture.foreignId(),
            "Synthetic foreign story");
        insertChapter(
            connection,
            fixture.ownerChapterId(),
            fixture.ownerWorkId(),
            fixture.ownerRevisionId(),
            fixture.ownerId(),
            OWNER_SENTINEL);
        insertChapter(
            connection,
            fixture.foreignChapterId(),
            fixture.foreignWorkId(),
            fixture.foreignRevisionId(),
            fixture.foreignId(),
            FOREIGN_SENTINEL);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private static void insertWork(
      Connection connection, UUID workId, UUID libraryId, UUID ownerId, String title)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO works (id, library_id, owner_user_id, title) VALUES (?, ?, ?, ?)",
        workId,
        libraryId,
        ownerId,
        title);
  }

  private static void insertChapter(
      Connection connection,
      UUID chapterId,
      UUID workId,
      UUID revisionId,
      UUID ownerId,
      String sentinel)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO chapters (id, work_id, ordinal, title, status) VALUES (?, ?, 1, ?, 'draft')",
        chapterId,
        workId,
        "Synthetic chapter");
    execute(
        connection,
        """
          INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, normalized_content, content_hash,
          created_by_user_id) VALUES (?, ?, 1, 'plain_text', ?, ?, ?)
        """,
        revisionId,
        chapterId,
        sentinel,
        hash(sentinel),
        ownerId);
    execute(
        connection,
        """
          INSERT INTO segments (id, chapter_revision_id, ordinal, segment_type, source_text, stable_anchor, checksum)
          VALUES (?, ?, 1, 'paragraph', ?, 'p-1', ?)
        """,
        UUID.randomUUID(),
        revisionId,
        sentinel,
        hash(sentinel));
  }

  private String read(ZipFile zip, String name) throws Exception {
    ZipEntry entry = zip.getEntry(name);
    assertTrue(entry != null, "Missing export entry: " + name);
    try (var input = zip.getInputStream(entry)) {
      String value = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      if (name.endsWith(".json") || name.endsWith(".jsonl")) {
        for (String line : value.split("\\R")) {
          if (!line.isBlank()) objectMapper.readTree(line);
        }
      }
      return value;
    }
  }

  private JsonNode readJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (Exception exception) {
      throw new AssertionError("Export dataset row is not valid JSON", exception);
    }
  }

  private void deleteFixture(Fixture fixture) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(
            connection,
            "DELETE FROM segments WHERE chapter_revision_id IN (?, ?)",
            fixture.ownerRevisionId(),
            fixture.foreignRevisionId());
        execute(
            connection,
            "DELETE FROM chapter_revisions WHERE id IN (?, ?)",
            fixture.ownerRevisionId(),
            fixture.foreignRevisionId());
        execute(
            connection,
            "DELETE FROM chapters WHERE id IN (?, ?)",
            fixture.ownerChapterId(),
            fixture.foreignChapterId());
        execute(
            connection,
            "DELETE FROM works WHERE id IN (?, ?)",
            fixture.ownerWorkId(),
            fixture.foreignWorkId());
        execute(
            connection,
            "DELETE FROM libraries WHERE id IN (?, ?)",
            fixture.ownerLibraryId(),
            fixture.foreignLibraryId());
        execute(
            connection,
            "DELETE FROM users WHERE id IN (?, ?)",
            fixture.ownerId(),
            fixture.foreignId());
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
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

  private static byte[] hash(String value) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record Fixture(
      UUID ownerId,
      UUID foreignId,
      UUID ownerLibraryId,
      UUID foreignLibraryId,
      UUID ownerWorkId,
      UUID foreignWorkId,
      UUID ownerChapterId,
      UUID foreignChapterId,
      UUID ownerRevisionId,
      UUID foreignRevisionId) {
    private Fixture() {
      this(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID());
    }
  }
}
