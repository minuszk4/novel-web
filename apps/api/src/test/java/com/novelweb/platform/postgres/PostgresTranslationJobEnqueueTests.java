package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.translation.jobs.TranslationJobRepository;
import com.novelweb.translation.jobs.TranslationJobService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresTranslationJobEnqueueTests {

  private final List<Fixture> fixtures = new ArrayList<>();

  @Autowired private DataSource dataSource;

  @Autowired private TranslationJobService translationJobService;

  @AfterEach
  void cleanFixtures() throws Exception {
    for (Fixture fixture : fixtures) deleteFixture(fixture);
    fixtures.clear();
  }

  @Test
  void enqueueStoresOnlyBoundedIntentAndOneIdentifierOnlyOutboxEvent() throws Exception {
    Fixture fixture = createFixture("vi");

    TranslationJobService.EnqueueResult result =
        translationJobService.enqueue(
            fixture.userId(),
            fixture.chapterId(),
            fixture.revisionId(),
            "en",
            "enqueue-job-key-0001");

    assertFalse(result.replayed());
    assertEquals(fixture.revisionId(), result.job().sourceRevisionId());
    assertEquals("vi", result.job().sourceLanguage());
    assertEquals("en", result.job().targetLanguage());
    assertEquals("fake-local", result.job().providerId());
    assertEquals("preparing", result.job().status());
    assertEquals(32, result.job().requestSha256().length);

    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT event.event_type, event.payload->>'jobId' AS payload_job_id,
                           event.payload->>'sourceRevisionId' AS payload_revision_id,
                           (event.payload - 'jobId' - 'sourceRevisionId') = '{}'::jsonb
                               AS only_identifiers,
                           job.profile_snapshot->>'mode' AS mode,
                           job.total_units, (SELECT COUNT(*) FROM translation_attempts attempt
                                             JOIN translation_units unit ON unit.id = attempt.unit_id
                                             WHERE unit.job_id = job.id) AS attempt_count
                    FROM translation_jobs job
                    JOIN translation_outbox_events event ON event.aggregate_id = job.id
                    WHERE job.id = ?
                    """)) {
      select.setObject(1, result.job().id());
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        assertEquals("translation.job.requested", row.getString("event_type"));
        assertEquals(result.job().id().toString(), row.getString("payload_job_id"));
        assertEquals(fixture.revisionId().toString(), row.getString("payload_revision_id"));
        assertTrue(row.getBoolean("only_identifiers"));
        assertEquals("fake-local", row.getString("mode"));
        assertEquals(0, row.getInt("total_units"));
        assertEquals(0, row.getInt("attempt_count"));
      }
    }
    assertEquals(1, countForChapter("translation_jobs", fixture.chapterId()));
    assertEquals(1, countForChapter("translation_outbox_events", fixture.chapterId()));
  }

  @Test
  void sameKeyAndCanonicalRequestReplayWhileDifferentRequestConflicts() throws Exception {
    Fixture fixture = createFixture("vi");
    TranslationJobService.EnqueueResult created =
        translationJobService.enqueue(
            fixture.userId(),
            fixture.chapterId(),
            fixture.revisionId(),
            "en",
            "stable-job-key-0001");

    TranslationJobService.EnqueueResult replayed =
        translationJobService.enqueue(
            fixture.userId(),
            fixture.chapterId(),
            fixture.revisionId(),
            "EN",
            "stable-job-key-0001");

    assertTrue(replayed.replayed());
    assertEquals(created.job().id(), replayed.job().id());
    assertEquals(created.job().createdAt(), replayed.job().createdAt());
    assertFalse(Arrays.equals(created.job().requestSha256(), new byte[32]));

    assertThrows(
        TranslationJobRepository.IdempotencyKeyReusedException.class,
        () ->
            translationJobService.enqueue(
                fixture.userId(),
                fixture.chapterId(),
                fixture.revisionId(),
                "en-US",
                "stable-job-key-0001"));

    assertEquals(1, countForChapter("translation_jobs", fixture.chapterId()));
    assertEquals(1, countForChapter("translation_outbox_events", fixture.chapterId()));
  }

  @Test
  void staleSourceRevisionIsRejectedWithoutCreatingAJobOrEvent() throws Exception {
    Fixture fixture = createFixture("vi");

    assertThrows(
        TranslationJobRepository.SourceRevisionStaleException.class,
        () ->
            translationJobService.enqueue(
                fixture.userId(),
                fixture.chapterId(),
                UUID.randomUUID(),
                "en",
                "stale-source-key-0001"));

    assertEquals(0, countForChapter("translation_jobs", fixture.chapterId()));
    assertEquals(0, countForChapter("translation_outbox_events", fixture.chapterId()));
  }

  @Test
  void concurrentSameKeyRequestsCreateOneJobAndOneOutboxEvent() throws Exception {
    Fixture fixture = createFixture("vi");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<TranslationJobService.EnqueueResult> first =
          executor.submit(
              () -> {
                start.await();
                return enqueue(fixture);
              });
      Future<TranslationJobService.EnqueueResult> second =
          executor.submit(
              () -> {
                start.await();
                return enqueue(fixture);
              });
      start.countDown();

      TranslationJobService.EnqueueResult firstResult = first.get(15, TimeUnit.SECONDS);
      TranslationJobService.EnqueueResult secondResult = second.get(15, TimeUnit.SECONDS);

      assertEquals(firstResult.job().id(), secondResult.job().id());
      assertNotEquals(firstResult.replayed(), secondResult.replayed());
      assertEquals(1, countForChapter("translation_jobs", fixture.chapterId()));
      assertEquals(1, countForChapter("translation_outbox_events", fixture.chapterId()));
    } finally {
      executor.shutdownNow();
    }
  }

  private TranslationJobService.EnqueueResult enqueue(Fixture fixture) {
    return translationJobService.enqueue(
        fixture.userId(),
        fixture.chapterId(),
        fixture.revisionId(),
        "en",
        "concurrent-job-key-001");
  }

  private Fixture createFixture(String sourceLanguage) throws SQLException {
    UUID userId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID segmentId = UUID.randomUUID();
    Fixture fixture = new Fixture(userId, libraryId, workId, chapterId, revisionId, segmentId);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(
            connection,
            "INSERT INTO users (id, display_name) VALUES (?, ?)",
            userId,
            "Translation enqueue fixture");
        execute(
            connection,
            "INSERT INTO libraries (id, owner_user_id, name, is_default) VALUES (?, ?, ?, TRUE)",
            libraryId,
            userId,
            "Translation enqueue fixture");
        execute(
            connection,
            "INSERT INTO works (id, library_id, owner_user_id, title) VALUES (?, ?, ?, ?)",
            workId,
            libraryId,
            userId,
            "Synthetic translation source");
        execute(
            connection,
            "INSERT INTO chapters (id, work_id, ordinal, title, source_language, status) "
                + "VALUES (?, ?, 1, ?, ?, 'ready')",
            chapterId,
            workId,
            "Chapter 1",
            sourceLanguage);
        execute(
            connection,
            "INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, "
                + "normalized_content, content_hash, created_by_user_id) "
                + "VALUES (?, ?, 1, 'plain_text', ?, ?, ?)",
            revisionId,
            chapterId,
            "Synthetic source sentence.",
            new byte[32],
            userId);
        execute(
            connection,
            "UPDATE chapters SET source_revision_id = ? WHERE id = ?",
            revisionId,
            chapterId);
        execute(
            connection,
            "INSERT INTO segments (id, chapter_revision_id, ordinal, segment_type, source_text, "
                + "stable_anchor, checksum) VALUES (?, ?, 1, 'paragraph', ?, 'p-1', ?)",
            segmentId,
            revisionId,
            "Synthetic source sentence.",
            new byte[32]);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
    fixtures.add(fixture);
    return fixture;
  }

  private void deleteFixture(Fixture fixture) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(
            connection, "DELETE FROM translation_jobs WHERE chapter_id = ?", fixture.chapterId());
        execute(connection, "DELETE FROM segments WHERE id = ?", fixture.segmentId());
        execute(connection, "DELETE FROM chapter_revisions WHERE id = ?", fixture.revisionId());
        execute(connection, "DELETE FROM chapters WHERE id = ?", fixture.chapterId());
        execute(connection, "DELETE FROM works WHERE id = ?", fixture.workId());
        execute(connection, "DELETE FROM libraries WHERE id = ?", fixture.libraryId());
        execute(connection, "DELETE FROM users WHERE id = ?", fixture.userId());
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private long countForChapter(String table, UUID chapterId) throws SQLException {
    if (!List.of("translation_jobs", "translation_outbox_events").contains(table)) {
      throw new IllegalArgumentException("unsupported fixture count table");
    }
    String sql =
        table.equals("translation_jobs")
            ? "SELECT COUNT(*) FROM translation_jobs WHERE chapter_id = ?"
            : "SELECT COUNT(*) FROM translation_outbox_events event "
                + "JOIN translation_jobs job ON job.id = event.aggregate_id "
                + "WHERE job.chapter_id = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, chapterId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getLong(1);
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

  private record Fixture(
      UUID userId, UUID libraryId, UUID workId, UUID chapterId, UUID revisionId, UUID segmentId) {}
}
