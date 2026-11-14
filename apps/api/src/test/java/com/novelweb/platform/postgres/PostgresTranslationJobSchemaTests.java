package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresTranslationJobSchemaTests {

  @Autowired private DataSource dataSource;

  @Test
  void jobIdempotencyAndSourceSnapshotForeignKeysAreEnforced() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Fixture fixture = createFixture(connection);
        Savepoint savepoint = connection.setSavepoint();

        SQLException duplicateKey =
            assertThrows(
                SQLException.class,
                () ->
                    insertJob(
                        connection,
                        UUID.randomUUID(),
                        fixture.userId(),
                        fixture.workId(),
                        fixture.chapterId(),
                        fixture.revisionId(),
                        "same-job-create-idempotency-key"));
        assertEquals("23505", duplicateKey.getSQLState());
        connection.rollback(savepoint);

        SQLException mismatchedSource =
            assertThrows(
                SQLException.class,
                () ->
                    insertJob(
                        connection,
                        UUID.randomUUID(),
                        fixture.userId(),
                        fixture.workId(),
                        fixture.chapterId(),
                        UUID.randomUUID(),
                        "different-job-create-idempotency-key"));
        assertEquals("23503", mismatchedSource.getSQLState());
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void workerLeaseColumnsMustMoveTogetherAndSourceSegmentsCannotBeQueuedTwice() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Fixture fixture = createFixture(connection);
        UUID firstUnitId = UUID.randomUUID();
        insertUnit(connection, fixture.jobId(), fixture.revisionId(), firstUnitId, 1);

        Savepoint leaseSavepoint = connection.setSavepoint();
        SQLException missingLease =
            assertThrows(
                SQLException.class,
                () ->
                    execute(
                        connection,
                        "UPDATE translation_units SET status = 'running' WHERE id = ?",
                        firstUnitId));
        assertEquals("23514", missingLease.getSQLState());
        connection.rollback(leaseSavepoint);

        try (PreparedStatement claim =
            connection.prepareStatement(
                "UPDATE translation_units SET status = 'running', lease_owner = ?, "
                    + "lease_token = ?, lease_until = ? WHERE id = ?")) {
          claim.setString(1, "translation-worker-test-1");
          claim.setObject(2, UUID.randomUUID());
          claim.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(60)));
          claim.setObject(4, firstUnitId);
          assertEquals(1, claim.executeUpdate());
        }

        insertUnitSegment(
            connection, fixture.jobId(), firstUnitId, fixture.revisionId(), fixture.segmentId());
        UUID secondUnitId = UUID.randomUUID();
        insertUnit(connection, fixture.jobId(), fixture.revisionId(), secondUnitId, 2);
        Savepoint duplicateSegmentSavepoint = connection.setSavepoint();
        SQLException duplicateSegment =
            assertThrows(
                SQLException.class,
                () ->
                    insertUnitSegment(
                        connection,
                        fixture.jobId(),
                        secondUnitId,
                        fixture.revisionId(),
                        fixture.segmentId()));
        assertEquals("23505", duplicateSegment.getSQLState());
        connection.rollback(duplicateSegmentSavepoint);
      } finally {
        connection.rollback();
      }
    }
  }

  private static Fixture createFixture(Connection connection) throws SQLException {
    UUID userId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID segmentId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();

    execute(
        connection,
        "INSERT INTO users (id, display_name) VALUES (?, ?)",
        userId,
        "Translation schema fixture");
    execute(
        connection,
        "INSERT INTO libraries (id, owner_user_id, name, is_default) VALUES (?, ?, ?, TRUE)",
        libraryId,
        userId,
        "Translation schema fixture");
    execute(
        connection,
        "INSERT INTO works (id, library_id, owner_user_id, title) VALUES (?, ?, ?, ?)",
        workId,
        libraryId,
        userId,
        "Synthetic translation source");
    execute(
        connection,
        "INSERT INTO chapters (id, work_id, ordinal, title, status) VALUES (?, ?, 1, ?, 'ready')",
        chapterId,
        workId,
        "Chapter 1");
    execute(
        connection,
        "INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, "
            + "normalized_content, content_hash, created_by_user_id) VALUES (?, ?, 1, 'plain_text', "
            + "?, ?, ?)",
        revisionId,
        chapterId,
        "Synthetic source sentence.",
        new byte[32],
        userId);
    execute(
        connection,
        "INSERT INTO segments (id, chapter_revision_id, ordinal, segment_type, source_text, "
            + "stable_anchor, checksum) VALUES (?, ?, 1, 'paragraph', ?, 'p-1', ?)",
        segmentId,
        revisionId,
        "Synthetic source sentence.",
        new byte[32]);
    insertJob(
        connection,
        jobId,
        userId,
        workId,
        chapterId,
        revisionId,
        "same-job-create-idempotency-key");
    return new Fixture(userId, workId, chapterId, revisionId, segmentId, jobId);
  }

  private static void insertJob(
      Connection connection,
      UUID jobId,
      UUID userId,
      UUID workId,
      UUID chapterId,
      UUID revisionId,
      String idempotencyKey)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO translation_jobs (id, requested_by_user_id, work_id, chapter_id, "
            + "source_revision_id, source_language, target_language, provider_id, profile_snapshot, "
            + "source_sha256, request_sha256, idempotency_key) "
            + "VALUES (?, ?, ?, ?, ?, 'vi', 'en', 'fake-local', '{}'::jsonb, ?, ?, ?)",
        jobId,
        userId,
        workId,
        chapterId,
        revisionId,
        new byte[32],
        new byte[32],
        idempotencyKey);
  }

  private static void insertUnit(
      Connection connection, UUID jobId, UUID revisionId, UUID unitId, int unitNo)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO translation_units (id, job_id, source_revision_id, unit_no, idempotency_key) "
            + "VALUES (?, ?, ?, ?, ?)",
        unitId,
        jobId,
        revisionId,
        unitNo,
        UUID.randomUUID());
  }

  private static void insertUnitSegment(
      Connection connection, UUID jobId, UUID unitId, UUID revisionId, UUID segmentId)
      throws SQLException {
    execute(
        connection,
        "INSERT INTO translation_unit_segments (job_id, unit_id, source_revision_id, "
            + "source_segment_id, segment_ordinal, source_sha256) VALUES (?, ?, ?, ?, 1, ?)",
        jobId,
        unitId,
        revisionId,
        segmentId,
        new byte[32]);
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
      UUID userId, UUID workId, UUID chapterId, UUID revisionId, UUID segmentId, UUID jobId) {}
}
