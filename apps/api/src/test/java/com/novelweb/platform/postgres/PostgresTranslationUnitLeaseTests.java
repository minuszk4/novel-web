package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.translation.jobs.TranslationAttemptRepository;
import com.novelweb.translation.jobs.TranslationAttemptService;
import com.novelweb.translation.jobs.TranslationJobService;
import com.novelweb.translation.jobs.TranslationUnitLeaseRepository;
import com.novelweb.translation.jobs.TranslationUnitLeaseService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
class PostgresTranslationUnitLeaseTests {

  private final List<Fixture> fixtures = new ArrayList<>();

  @Autowired private DataSource dataSource;

  @Autowired private TranslationJobService jobService;

  @Autowired private TranslationUnitLeaseService leaseService;

  @Autowired private TranslationAttemptService attemptService;

  @AfterEach
  void cleanFixtures() throws Exception {
    for (Fixture fixture : fixtures) deleteFixture(fixture);
    fixtures.clear();
  }

  @Test
  void competingWorkersClaimDifferentUnitsAtomically() throws Exception {
    Fixture fixture = createQueuedJob(101);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Optional<TranslationUnitLeaseRepository.Lease>> first =
          executor.submit(() -> claimAfterBarrier("translation-worker-a", ready, start));
      Future<Optional<TranslationUnitLeaseRepository.Lease>> second =
          executor.submit(() -> claimAfterBarrier("translation-worker-b", ready, start));

      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      TranslationUnitLeaseRepository.Lease firstLease =
          first.get(10, TimeUnit.SECONDS).orElseThrow();
      TranslationUnitLeaseRepository.Lease secondLease =
          second.get(10, TimeUnit.SECONDS).orElseThrow();

      assertEquals(fixture.jobId(), firstLease.jobId());
      assertEquals(fixture.jobId(), secondLease.jobId());
      assertNotEquals(firstLease.unitId(), secondLease.unitId());
      assertNotEquals(firstLease.leaseToken(), secondLease.leaseToken());
      assertEquals(
          List.of(1, 2),
          List.of(firstLease.unitNo(), secondLease.unitNo()).stream().sorted().toList());
      assertEquals(0, firstLease.attemptCount());
      assertEquals(0, secondLease.attemptCount());
      assertJobProgress(fixture.jobId(), "translating", 0, 0, true);
      assertEquals(2, countRunningUnits(fixture.jobId()));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void claimSkipsAUnitLockedByAnotherTransaction() throws Exception {
    Fixture fixture = createQueuedJob(101);
    try (Connection lockConnection = dataSource.getConnection()) {
      lockConnection.setAutoCommit(false);
      try (PreparedStatement lockFirstUnit =
          lockConnection.prepareStatement(
              "SELECT id FROM translation_units WHERE job_id = ? AND unit_no = 1 FOR UPDATE")) {
        lockFirstUnit.setObject(1, fixture.jobId());
        try (ResultSet row = lockFirstUnit.executeQuery()) {
          assertTrue(row.next());
        }
      }

      TranslationUnitLeaseRepository.Lease skippedLockLease =
          leaseService
              .claimNext("translation-worker-skip-lock", Duration.ofSeconds(60))
              .orElseThrow();
      assertEquals(2, skippedLockLease.unitNo());
      lockConnection.commit();
    }

    TranslationUnitLeaseRepository.Lease remainingLease =
        leaseService
            .claimNext("translation-worker-remaining", Duration.ofSeconds(60))
            .orElseThrow();
    assertEquals(1, remainingLease.unitNo());
    assertEquals(fixture.jobId(), remainingLease.jobId());
  }

  @Test
  void expiredLeaseIsReclaimedAndOldWorkerCannotRenewTheReclaimedLease() throws Exception {
    Fixture fixture = createQueuedJob(1);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement corruptProgress =
            connection.prepareStatement(
                "UPDATE translation_jobs SET completed_units = 1 WHERE id = ?")) {
      corruptProgress.setObject(1, fixture.jobId());
      assertEquals(1, corruptProgress.executeUpdate());
    }

    TranslationUnitLeaseRepository.Lease firstLease =
        leaseService.claimNext("translation-worker-old", Duration.ofSeconds(30)).orElseThrow();
    expireLease(firstLease.unitId());
    assertTrue(leaseService.heartbeat(firstLease, Duration.ofSeconds(60)).isEmpty());

    TranslationUnitLeaseRepository.Lease reclaimedLease =
        leaseService.claimNext("translation-worker-new", Duration.ofSeconds(60)).orElseThrow();

    assertEquals(firstLease.unitId(), reclaimedLease.unitId());
    assertNotEquals(firstLease.leaseToken(), reclaimedLease.leaseToken());
    assertEquals("translation-worker-new", reclaimedLease.workerId());
    assertEquals(0, reclaimedLease.attemptCount());
    assertTrue(leaseService.heartbeat(firstLease, Duration.ofSeconds(120)).isEmpty());
    TranslationUnitLeaseRepository.Lease renewedLease =
        leaseService.heartbeat(reclaimedLease, Duration.ofSeconds(120)).orElseThrow();
    assertEquals(reclaimedLease.leaseToken(), renewedLease.leaseToken());
    assertTrue(renewedLease.leaseUntil().isAfter(reclaimedLease.leaseUntil()));
    assertEquals("translating", readJobStatus(fixture.jobId()));
    assertJobProgress(fixture.jobId(), "translating", 0, 0, true);
    assertEquals(1, countRunningUnits(fixture.jobId()));
  }

  @Test
  void attemptStartIsBodyFreeAndIdempotentForTheSameLease() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService.claimNext("translation-attempt-worker", Duration.ofSeconds(60)).orElseThrow();

    TranslationAttemptRepository.StartResult first = attemptService.begin(lease);
    TranslationAttemptRepository.StartResult duplicate = attemptService.begin(lease);

    assertEquals(1, first.attemptNo());
    assertEquals("fake-local", first.providerId());
    assertEquals(32, first.requestSha256().length);
    assertFalse(first.replayed());
    assertTrue(first.dispatchAllowed());
    assertEquals(first.attemptId(), duplicate.attemptId());
    assertTrue(duplicate.replayed());
    assertFalse(duplicate.dispatchAllowed());
    assertEquals(first.requestSha256().length, duplicate.requestSha256().length);
    assertEquals(1, readAttemptCount(lease.unitId()));
    assertEquals(1, countAttempts(lease.unitId()));
    assertEquals(1, countAttemptRequestedEvents(lease.unitId()));
    assertAttemptContainsOnlySafeMetadata(first);
    assertAttemptRequestedEventContainsOnlyIdentifiers(first);
    assertEquals(fixture.jobId(), first.jobId());
  }

  @Test
  void staleLeaseCannotStartAttemptAndReclaimedRunningAttemptBlocksAmbiguousRedispatch()
      throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease firstLease =
        leaseService.claimNext("translation-attempt-old", Duration.ofSeconds(60)).orElseThrow();
    TranslationAttemptRepository.StartResult firstAttempt = attemptService.begin(firstLease);
    expireLease(firstLease.unitId());
    TranslationUnitLeaseRepository.Lease reclaimedLease =
        leaseService.claimNext("translation-attempt-new", Duration.ofSeconds(60)).orElseThrow();

    assertThrows(
        TranslationAttemptRepository.StaleLeaseException.class,
        () -> attemptService.begin(firstLease));
    assertThrows(
        TranslationAttemptRepository.UnresolvedAttemptException.class,
        () -> attemptService.begin(reclaimedLease));

    assertEquals(firstAttempt.attemptId(), findOnlyAttemptId(firstLease.unitId()));
    assertEquals(1, readAttemptCount(firstLease.unitId()));
    assertEquals("translating", readJobStatus(fixture.jobId()));
  }

  @Test
  void attemptCountCannotExceedUnitMaximum() throws Exception {
    Fixture fixture = createQueuedJob(1);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE translation_units SET max_attempts = 1 WHERE job_id = ?")) {
      update.setObject(1, fixture.jobId());
      assertEquals(1, update.executeUpdate());
    }
    TranslationUnitLeaseRepository.Lease firstLease =
        leaseService.claimNext("translation-attempt-cap-a", Duration.ofSeconds(60)).orElseThrow();
    attemptService.begin(firstLease);
    expireLease(firstLease.unitId());
    TranslationUnitLeaseRepository.Lease secondLease =
        leaseService.claimNext("translation-attempt-cap-b", Duration.ofSeconds(60)).orElseThrow();

    assertThrows(
        TranslationAttemptRepository.AttemptsExhaustedException.class,
        () -> attemptService.begin(secondLease));
    assertEquals(1, readAttemptCount(firstLease.unitId()));
    assertEquals(1, countAttempts(firstLease.unitId()));
  }

  private Optional<TranslationUnitLeaseRepository.Lease> claimAfterBarrier(
      String workerId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("claim barrier timed out");
    return leaseService.claimNext(workerId, Duration.ofSeconds(60));
  }

  private Fixture createQueuedJob(int segmentCount) throws Exception {
    UUID userId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID jobId;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(
            connection,
            "INSERT INTO users (id, display_name) VALUES (?, ?)",
            userId,
            "Translation lease fixture");
        execute(
            connection,
            "INSERT INTO libraries (id, owner_user_id, name, is_default) VALUES (?, ?, ?, TRUE)",
            libraryId,
            userId,
            "Translation lease fixture");
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
                + "VALUES (?, ?, 1, ?, 'vi', 'ready')",
            chapterId,
            workId,
            "Chapter 1");
        execute(
            connection,
            "INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, "
                + "normalized_content, content_hash, created_by_user_id) "
                + "VALUES (?, ?, 1, 'plain_text', ?, ?, ?)",
            revisionId,
            chapterId,
            "Synthetic source chapter",
            sha256("Synthetic source chapter"),
            userId);
        execute(
            connection,
            "UPDATE chapters SET source_revision_id = ? WHERE id = ?",
            revisionId,
            chapterId);
        try (PreparedStatement insertSegment =
            connection.prepareStatement(
                "INSERT INTO segments (id, chapter_revision_id, ordinal, segment_type, "
                    + "source_text, stable_anchor, checksum) "
                    + "VALUES (?, ?, ?, 'paragraph', ?, ?, ?)")) {
          for (int ordinal = 1; ordinal <= segmentCount; ordinal++) {
            String sourceText = "Synthetic source sentence " + ordinal + ".";
            insertSegment.setObject(1, UUID.randomUUID());
            insertSegment.setObject(2, revisionId);
            insertSegment.setInt(3, ordinal);
            insertSegment.setString(4, sourceText);
            insertSegment.setString(5, "p-" + ordinal);
            insertSegment.setBytes(6, sha256(sourceText));
            insertSegment.addBatch();
          }
          insertSegment.executeBatch();
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }

    String idempotencyKey = "lease-job-key-" + UUID.randomUUID().toString().replace("-", "");
    var job = jobService.enqueue(userId, chapterId, revisionId, "en", idempotencyKey).job();
    jobId = job.id();
    Fixture fixture = new Fixture(userId, libraryId, workId, chapterId, revisionId, jobId);
    fixtures.add(fixture);
    return fixture;
  }

  private void deleteFixture(Fixture fixture) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(connection, "DELETE FROM translation_jobs WHERE id = ?", fixture.jobId());
        execute(
            connection, "DELETE FROM segments WHERE chapter_revision_id = ?", fixture.revisionId());
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

  private void expireLease(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE translation_units SET lease_until = clock_timestamp() - "
                    + "INTERVAL '1 second', heartbeat_at = clock_timestamp() - "
                    + "INTERVAL '2 seconds' WHERE id = ?")) {
      update.setObject(1, unitId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void assertJobProgress(
      UUID jobId, String status, int completedUnits, int failedUnits, boolean started)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT status, completed_units, failed_units, started_at FROM translation_jobs "
                    + "WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        assertEquals(status, row.getString("status"));
        assertEquals(completedUnits, row.getInt("completed_units"));
        assertEquals(failedUnits, row.getInt("failed_units"));
        assertEquals(started, row.getObject("started_at") != null);
      }
    }
  }

  private long countRunningUnits(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM translation_units WHERE job_id = ? AND status = 'running'")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private String readJobStatus(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT status FROM translation_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private int readAttemptCount(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT attempt_count FROM translation_units WHERE id = ?")) {
      select.setObject(1, unitId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private long countAttempts(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM translation_attempts WHERE unit_id = ?")) {
      select.setObject(1, unitId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private UUID findOnlyAttemptId(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT id FROM translation_attempts WHERE unit_id = ?")) {
      select.setObject(1, unitId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        UUID attemptId = row.getObject(1, UUID.class);
        assertFalse(row.next());
        return attemptId;
      }
    }
  }

  private void assertAttemptContainsOnlySafeMetadata(
      TranslationAttemptRepository.StartResult result) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT provider_id, outcome, request_sha256, failure_code, provider_request_id "
                    + "FROM translation_attempts WHERE id = ?")) {
      select.setObject(1, result.attemptId());
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        assertEquals("fake-local", row.getString("provider_id"));
        assertEquals("running", row.getString("outcome"));
        assertEquals(result.requestSha256().length, row.getBytes("request_sha256").length);
        assertTrue(row.getString("failure_code") == null);
        assertTrue(row.getString("provider_request_id") == null);
      }
    }
  }

  private long countAttemptRequestedEvents(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM translation_outbox_events "
                    + "WHERE aggregate_id = (SELECT job_id FROM translation_units WHERE id = ?) "
                    + "AND event_type = 'translation.unit.attempt_requested'")) {
      select.setObject(1, unitId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private void assertAttemptRequestedEventContainsOnlyIdentifiers(
      TranslationAttemptRepository.StartResult result) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT event_type, correlation_id, deduplication_key,
                           payload->>'jobId' AS payload_job_id,
                           payload->>'unitId' AS payload_unit_id,
                           payload->>'attemptId' AS payload_attempt_id,
                           payload->>'attemptNo' AS payload_attempt_no,
                           (payload - 'jobId' - 'unitId' - 'attemptId' - 'attemptNo') = '{}'::jsonb
                               AS only_identifiers
                    FROM translation_outbox_events WHERE correlation_id = ?
                    """)) {
      select.setObject(1, result.attemptId());
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        assertEquals("translation.unit.attempt_requested", row.getString("event_type"));
        assertEquals(result.attemptId(), row.getObject("correlation_id", UUID.class));
        assertEquals("attempt-start-" + result.attemptId(), row.getString("deduplication_key"));
        assertEquals(result.jobId().toString(), row.getString("payload_job_id"));
        assertEquals(result.unitId().toString(), row.getString("payload_unit_id"));
        assertEquals(result.attemptId().toString(), row.getString("payload_attempt_id"));
        assertEquals("1", row.getString("payload_attempt_no"));
        assertTrue(row.getBoolean("only_identifiers"));
        assertFalse(row.next());
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

  private static byte[] sha256(String value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
  }

  private record Fixture(
      UUID userId, UUID libraryId, UUID workId, UUID chapterId, UUID revisionId, UUID jobId) {}
}
