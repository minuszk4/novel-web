package com.novelweb.platform.postgres;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.translation.jobs.TranslationAttemptCompletionRepository;
import com.novelweb.translation.jobs.TranslationAttemptCompletionService;
import com.novelweb.translation.jobs.TranslationAttemptFailureRepository;
import com.novelweb.translation.jobs.TranslationAttemptFailureService;
import com.novelweb.translation.jobs.TranslationAttemptRepository;
import com.novelweb.translation.jobs.TranslationAttemptService;
import com.novelweb.translation.jobs.TranslationCandidateEditRepository;
import com.novelweb.translation.jobs.TranslationCandidateEditService;
import com.novelweb.translation.jobs.TranslationCandidatePreviewRepository;
import com.novelweb.translation.jobs.TranslationCandidatePreviewService;
import com.novelweb.translation.jobs.TranslationFakeLocalWorkerService;
import com.novelweb.translation.jobs.TranslationJobCancelRepository;
import com.novelweb.translation.jobs.TranslationJobCancelService;
import com.novelweb.translation.jobs.TranslationJobController;
import com.novelweb.translation.jobs.TranslationJobRetryRepository;
import com.novelweb.translation.jobs.TranslationJobRetryService;
import com.novelweb.translation.jobs.TranslationJobService;
import com.novelweb.translation.jobs.TranslationJobStatusService;
import com.novelweb.translation.jobs.TranslationUnitLeaseRepository;
import com.novelweb.translation.jobs.TranslationUnitLeaseService;
import com.novelweb.translation.provider.TranslationBillingUnit;
import com.novelweb.translation.provider.TranslationProviderException;
import com.novelweb.translation.provider.TranslationProviderFailure;
import com.novelweb.translation.provider.TranslationResponse;
import com.novelweb.translation.provider.TranslationResponse.SegmentResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest
@ActiveProfiles({"postgres", "translation-fake"})
@org.springframework.test.context.TestPropertySource(
    properties = {
      "novel.translation.fake.enabled=true",
      "novel.library.cursor.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    })
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresTranslationUnitLeaseTests {

  private final List<Fixture> fixtures = new ArrayList<>();
  private final Map<UUID, UUID> workspaceByLibrary = new HashMap<>();
  private final Map<UUID, List<UUID>> additionalJobsByFixture = new HashMap<>();
  private final List<UUID> extraUserFixtures = new ArrayList<>();

  @Autowired private DataSource dataSource;

  @Autowired private TranslationJobService jobService;

  @Autowired private TranslationJobRetryService jobRetryService;

  @Autowired private TranslationJobCancelService jobCancelService;

  @Autowired private TranslationJobStatusService jobStatusService;

  @Autowired private TranslationCandidatePreviewService candidatePreviewService;

  @Autowired private TranslationCandidateEditService candidateEditService;

  @Autowired private TranslationUnitLeaseService leaseService;

  @Autowired private TranslationAttemptService attemptService;

  @Autowired private TranslationAttemptCompletionService completionService;

  @Autowired private TranslationAttemptFailureService failureService;

  @Autowired private TranslationFakeLocalWorkerService fakeLocalWorkerService;

  @AfterEach
  void cleanFixtures() throws Exception {
    for (Fixture fixture : fixtures) deleteFixture(fixture);
    fixtures.clear();
    try (Connection connection = dataSource.getConnection()) {
      for (UUID userId : extraUserFixtures) {
        execute(connection, "DELETE FROM users WHERE id = ?", userId);
      }
    }
    extraUserFixtures.clear();
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
  void fakeLocalAttemptCanResumeAfterLeaseReclaimWithoutCreatingAnotherAttempt() throws Exception {
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
    TranslationAttemptRepository.StartResult resumed = attemptService.begin(reclaimedLease);

    assertEquals(firstAttempt.attemptId(), findOnlyAttemptId(firstLease.unitId()));
    assertEquals(firstAttempt.attemptId(), resumed.attemptId());
    assertTrue(resumed.replayed());
    assertTrue(resumed.dispatchAllowed());
    assertEquals(1, readAttemptCount(firstLease.unitId()));
    assertEquals(1, countAttemptRequestedEvents(firstLease.unitId()));
    assertEquals("translating", readJobStatus(fixture.jobId()));
  }

  @Test
  void ambiguousNonFakeProviderAttemptCannotBeAutomaticallyResumed() throws Exception {
    Fixture fixture = createQueuedJob(1);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE translation_jobs SET provider_id = 'deep-l' WHERE id = ?")) {
      update.setObject(1, fixture.jobId());
      assertEquals(1, update.executeUpdate());
    }
    TranslationUnitLeaseRepository.Lease oldLease =
        leaseService
            .claimNext("translation-attempt-real-old", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult oldAttempt = attemptService.begin(oldLease);
    expireLease(oldLease.unitId());
    TranslationUnitLeaseRepository.Lease currentLease =
        leaseService
            .claimNext("translation-attempt-real-current", Duration.ofSeconds(60))
            .orElseThrow();

    assertThrows(
        TranslationAttemptRepository.UnresolvedAttemptException.class,
        () -> attemptService.begin(currentLease));

    assertEquals(oldAttempt.attemptId(), findOnlyAttemptId(oldLease.unitId()));
    assertEquals(1, readAttemptCount(oldLease.unitId()));
    assertEquals(1, countAttemptRequestedEvents(oldLease.unitId()));
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
    TranslationAttemptRepository.StartResult firstAttempt = attemptService.begin(firstLease);
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "UPDATE translation_attempts SET outcome = 'temporary', "
              + "completed_at = clock_timestamp() WHERE id = ?",
          firstAttempt.attemptId());
      execute(
          connection,
          "UPDATE translation_units SET status = 'retry_wait', lease_owner = NULL, "
              + "lease_token = NULL, lease_until = NULL, heartbeat_at = NULL "
              + "WHERE id = ?",
          firstLease.unitId());
    }
    TranslationUnitLeaseRepository.Lease secondLease =
        leaseService.claimNext("translation-attempt-cap-b", Duration.ofSeconds(60)).orElseThrow();

    assertThrows(
        TranslationAttemptRepository.AttemptsExhaustedException.class,
        () -> attemptService.begin(secondLease));
    assertEquals(1, readAttemptCount(firstLease.unitId()));
    assertEquals(1, countAttempts(firstLease.unitId()));
  }

  @Test
  void successfulCompletionStoresCandidateAndPublishesOnlyAfterEveryUnitSucceeds()
      throws Exception {
    Fixture fixture = createQueuedJob(101);
    TranslationUnitLeaseRepository.Lease firstLease =
        leaseService.claimNext("translation-complete-a", Duration.ofSeconds(60)).orElseThrow();
    TranslationUnitLeaseRepository.Lease secondLease =
        leaseService.claimNext("translation-complete-b", Duration.ofSeconds(60)).orElseThrow();
    TranslationAttemptRepository.StartResult firstAttempt = attemptService.begin(firstLease);
    TranslationAttemptRepository.StartResult secondAttempt = attemptService.begin(secondLease);

    TranslationAttemptCompletionRepository.CompletionResult firstResult =
        completionService.complete(
            firstLease, response(firstAttempt.attemptId(), firstLease.unitId()));
    TranslationAttemptCompletionRepository.CompletionResult secondResult =
        completionService.complete(
            secondLease, response(secondAttempt.attemptId(), secondLease.unitId()));
    TranslationAttemptCompletionRepository.CompletionResult replay =
        completionService.complete(
            secondLease, response(secondAttempt.attemptId(), secondLease.unitId()));

    assertEquals("partial", firstResult.candidateStatus());
    assertEquals("translating", firstResult.jobStatus());
    assertEquals("ready_for_review", secondResult.candidateStatus());
    assertEquals("review_required", secondResult.jobStatus());
    assertEquals(firstResult.candidateId(), secondResult.candidateId());
    assertTrue(replay.replayed());
    assertEquals(secondResult.candidateId(), replay.candidateId());
    assertEquals(1, countCandidates(fixture.jobId()));
    assertEquals(101, countCandidateSegments(secondResult.candidateId()));
    assertEquals(2, countUsageRows(fixture.jobId()));
    assertEquals(1, countCandidateReadyEvents(fixture.jobId()));
    assertEquals(2, countUnitCompletedEvents(fixture.jobId()));
    assertEquals("review_required", readJobStatus(fixture.jobId()));
    assertEquals(2, readCompletedUnits(fixture.jobId()));
    assertEquals("succeeded", readUnitStatus(firstLease.unitId()));
    assertEquals("succeeded", readUnitStatus(secondLease.unitId()));
    assertEquals("succeeded", readAttemptOutcome(secondAttempt.attemptId()));
    UUID persistedSegmentId = sourceSegmentIds(secondLease.unitId()).getFirst();
    assertEquals(
        "[fake vi->en] translated " + persistedSegmentId,
        readCandidateTranslation(secondResult.candidateId(), persistedSegmentId));
    assertEquals(32, readAttemptResponseHash(secondAttempt.attemptId()).length);
  }

  @Test
  void reclaimedLeaseCannotPersistStaleCandidateOrCompleteTheUnit() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease oldLease =
        leaseService.claimNext("translation-complete-old", Duration.ofSeconds(60)).orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(oldLease);
    expireLease(oldLease.unitId());
    TranslationUnitLeaseRepository.Lease currentLease =
        leaseService
            .claimNext("translation-complete-current", Duration.ofSeconds(60))
            .orElseThrow();

    assertThrows(
        TranslationAttemptCompletionRepository.StaleLeaseException.class,
        () ->
            completionService.complete(oldLease, response(attempt.attemptId(), oldLease.unitId())));

    assertEquals("running", readUnitStatus(currentLease.unitId()));
    assertEquals("running", readAttemptOutcome(attempt.attemptId()));
    assertEquals(0, countCandidates(fixture.jobId()));
    assertEquals(0, countCandidateSegmentsForJob(fixture.jobId()));
    assertEquals(0, countUnitCompletedEvents(fixture.jobId()));
    assertEquals(1, readAttemptCount(currentLease.unitId()));
  }

  @Test
  void incompleteProviderOutputLeavesAttemptAndUnitUnchanged() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService
            .claimNext("translation-complete-invalid", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);
    TranslationResponse invalid =
        new TranslationResponse(
            attempt.attemptId(),
            "fake-" + attempt.attemptId(),
            "fake-local",
            List.of(new SegmentResult(UUID.randomUUID(), "unmapped output")),
            0,
            TranslationBillingUnit.CODE_POINT);

    assertThrows(
        TranslationAttemptCompletionRepository.InvalidCompletionException.class,
        () -> completionService.complete(lease, invalid));

    assertEquals("running", readUnitStatus(lease.unitId()));
    assertEquals("running", readAttemptOutcome(attempt.attemptId()));
    assertEquals(0, countCandidates(fixture.jobId()));
    assertEquals(0, countUnitCompletedEvents(fixture.jobId()));
    assertEquals(0, countUsageRows(fixture.jobId()));
  }

  @Test
  void changedSourceRevisionCannotBecomeAReviewableCandidate() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService
            .claimNext("translation-complete-stale-source", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement("UPDATE chapters SET status = 'draft' WHERE id = ?")) {
      update.setObject(1, fixture.chapterId());
      assertEquals(1, update.executeUpdate());
    }

    assertThrows(
        TranslationAttemptCompletionRepository.StaleSourceException.class,
        () -> completionService.complete(lease, response(attempt.attemptId(), lease.unitId())));

    assertEquals("running", readUnitStatus(lease.unitId()));
    assertEquals("running", readAttemptOutcome(attempt.attemptId()));
    assertEquals(0, countCandidates(fixture.jobId()));
    assertEquals(0, countUnitCompletedEvents(fixture.jobId()));
    assertEquals(0, countUsageRows(fixture.jobId()));
  }

  @Test
  void explicitFakeWorkerLoadsBoundedSourceRunsOfflineAndPersistsEstimateAndCandidate()
      throws Exception {
    Fixture fixture = createQueuedJob(1);

    TranslationFakeLocalWorkerService.WorkResult result =
        fakeLocalWorkerService
            .processNext("translation-fake-worker", Duration.ofSeconds(60))
            .orElseThrow();
    UUID sourceSegmentId = sourceSegmentIds(result.unitId()).getFirst();

    assertTrue(result.dispatched());
    assertFalse(result.resumed());
    assertEquals(fixture.jobId(), result.jobId());
    assertEquals("ready_for_review", result.completion().candidateStatus());
    assertEquals("review_required", result.completion().jobStatus());
    assertEquals(
        "[fake vi->en] " + readSourceText(sourceSegmentId),
        readCandidateTranslation(result.completion().candidateId(), sourceSegmentId));
    assertEquals(0, readAttemptEstimate(result.attemptId()).compareTo(BigDecimal.ZERO));
    assertEquals("USD", readAttemptEstimateCurrency(result.attemptId()));
    assertEquals("CODE_POINT", readAttemptEstimateUnit(result.attemptId()));
    assertEquals("fake-free-v1", readAttemptPricingVersion(result.attemptId()));
    assertEquals("succeeded", readAttemptOutcome(result.attemptId()));
    assertEquals(1, countUsageRows(fixture.jobId()));
    assertEquals(readAttemptBilledUnits(result.attemptId()), readUsageUnits(result.attemptId()));
    assertEquals("CODE_POINT", readUsageBillingUnit(result.attemptId()));
    assertEquals("fake-local", readUsageProvider(result.attemptId()));
    assertEquals("fake-local", readUsageModel(result.attemptId()));
    assertEquals("fake-" + result.attemptId(), readUsageRequestId(result.attemptId()));
    assertEquals(1, countAttemptRequestedEvents(result.unitId()));
    assertEquals(1, countUnitCompletedEvents(fixture.jobId()));

    try (Connection connection = dataSource.getConnection()) {
      SQLException updateFailure =
          assertThrows(
              SQLException.class,
              () ->
                  execute(
                      connection,
                      "UPDATE translation_usage_ledger SET confirmed_units = 0 WHERE attempt_id = ?",
                      result.attemptId()));
      assertEquals("55000", updateFailure.getSQLState());

      SQLException deleteFailure =
          assertThrows(
              SQLException.class,
              () ->
                  execute(
                      connection,
                      "DELETE FROM translation_usage_ledger WHERE attempt_id = ?",
                      result.attemptId()));
      assertEquals("55000", deleteFailure.getSQLState());

      SQLException truncateFailure =
          assertThrows(
              SQLException.class, () -> execute(connection, "TRUNCATE translation_usage_ledger"));
      assertEquals("55000", truncateFailure.getSQLState());
    }
    assertEquals(1, countUsageRows(fixture.jobId()));
    assertTrue(
        fakeLocalWorkerService
            .processNext("translation-fake-worker", Duration.ofSeconds(60))
            .isEmpty());
  }

  @Test
  void usageLedgerRejectsAnEntryBeforeItsAttemptSucceeds() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService
            .claimNext("translation-usage-ledger-worker", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);

    SQLException rejection;
    try (Connection connection = dataSource.getConnection()) {
      rejection =
          assertThrows(
              SQLException.class,
              () ->
                  execute(
                      connection,
                      """
                          INSERT INTO translation_usage_ledger
                              (attempt_id, provider_id, provider_model, provider_request_id,
                               confirmed_units, billing_unit)
                          VALUES (?, 'fake-local', 'fake-local', ?, 0, 'CODE_POINT')
                          """,
                      attempt.attemptId(),
                      "fake-" + attempt.attemptId()));
    }

    assertEquals("23514", rejection.getSQLState());
    assertEquals("running", readAttemptOutcome(attempt.attemptId()));
    assertEquals("running", readUnitStatus(lease.unitId()));
    assertEquals(0, countUsageRows(fixture.jobId()));
  }

  @Test
  void onlyOneCurrentTranslationVersionCanExistPerChapterAndTargetLanguage() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationFakeLocalWorkerService.WorkResult firstJob =
        fakeLocalWorkerService
            .processNext("translation-version-first-worker", Duration.ofSeconds(60))
            .orElseThrow();
    UUID secondJobId =
        jobService
            .enqueue(
                fixture.userId(),
                fixture.chapterId(),
                fixture.revisionId(),
                "en",
                "version-job-key-" + UUID.randomUUID().toString().replace("-", ""))
            .job()
            .id();
    additionalJobsByFixture
        .computeIfAbsent(fixture.jobId(), ignored -> new ArrayList<>())
        .add(secondJobId);
    TranslationFakeLocalWorkerService.WorkResult secondJob =
        fakeLocalWorkerService
            .processNext("translation-version-second-worker", Duration.ofSeconds(60))
            .orElseThrow();

    assertEquals(fixture.jobId(), firstJob.jobId());
    assertEquals(secondJobId, secondJob.jobId());
    insertCurrentTranslationVersion(fixture, firstJob.completion().candidateId(), 1);
    SQLException conflict =
        assertThrows(
            SQLException.class,
            () ->
                insertCurrentTranslationVersion(fixture, secondJob.completion().candidateId(), 2));

    assertEquals("23505", conflict.getSQLState());
    assertEquals(1, countCurrentTranslationVersions(fixture.chapterId()));
  }

  @Test
  void fakeWorkerResumesTheSameDeterministicAttemptAfterExpiredLease() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease originalLease =
        leaseService.claimNext("translation-fake-worker-old", Duration.ofSeconds(60)).orElseThrow();
    TranslationAttemptRepository.StartResult originalAttempt = attemptService.begin(originalLease);
    expireLease(originalLease.unitId());

    TranslationFakeLocalWorkerService.WorkResult result =
        fakeLocalWorkerService
            .processNext("translation-fake-worker-restarted", Duration.ofSeconds(60))
            .orElseThrow();

    assertTrue(result.dispatched());
    assertTrue(result.resumed());
    assertEquals(originalAttempt.attemptId(), result.attemptId());
    assertEquals(1, readAttemptCount(originalLease.unitId()));
    assertEquals(1, countAttempts(originalLease.unitId()));
    assertEquals(1, countAttemptRequestedEvents(originalLease.unitId()));
    assertEquals(1, countUnitCompletedEvents(fixture.jobId()));
    assertEquals("review_required", readJobStatus(fixture.jobId()));
  }

  @Test
  void safeFakeLocalFailureSchedulesBoundedRetryThenPermanentFailureIsTerminal() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease firstLease =
        leaseService
            .claimNext("translation-fake-failure-worker", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult firstAttempt = attemptService.begin(firstLease);

    TranslationAttemptFailureRepository.FailureResult retry =
        failureService.record(
            firstLease,
            firstAttempt.attemptId(),
            new TranslationProviderException(TranslationProviderFailure.TIMEOUT, null));

    assertTrue(retry.retryScheduled());
    assertTrue(retry.retryAt().isAfter(Instant.now()));
    assertEquals("retry_wait", retry.jobStatus());
    assertEquals("retry_wait", readUnitStatus(firstLease.unitId()));
    assertEquals("timeout", readAttemptOutcome(firstAttempt.attemptId()));
    assertEquals("TIMEOUT", readAttemptFailureCode(firstAttempt.attemptId()));
    assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.unit.retry_scheduled"));
    assertEquals(0, countOutboxEvents(fixture.jobId(), "translation.unit.failed"));
    assertEquals(0, countUsageRows(fixture.jobId()));

    makeUnitAvailableNow(firstLease.unitId());
    TranslationUnitLeaseRepository.Lease secondLease =
        leaseService
            .claimNext("translation-fake-failure-worker-retry", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult secondAttempt = attemptService.begin(secondLease);
    TranslationAttemptFailureRepository.FailureResult terminal =
        failureService.record(
            secondLease,
            secondAttempt.attemptId(),
            new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null));

    assertFalse(terminal.retryScheduled());
    assertEquals("invalid_response", terminal.outcome());
    assertEquals("failed", terminal.jobStatus());
    assertEquals("failed", readUnitStatus(firstLease.unitId()));
    assertEquals("INVALID_RESPONSE", readUnitFailureCode(firstLease.unitId()));
    assertEquals("failed", readJobStatus(fixture.jobId()));
    assertJobProgress(fixture.jobId(), "failed", 0, 1, true);
    assertEquals(2, readAttemptCount(firstLease.unitId()));
    assertEquals("invalid_response", readAttemptOutcome(secondAttempt.attemptId()));
    assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.unit.retry_scheduled"));
    assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.unit.failed"));
    assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.job.failed"));
    assertEquals(0, countUsageRows(fixture.jobId()));
    assertTrue(outboxPayloadsContainIdentifiersOnly(fixture.jobId()));
  }

  @Test
  void sanitizedProviderFailureMatrixNeverChangesSourceOrCreatesCurrentTranslation()
      throws Exception {
    List<TranslationProviderException> failures =
        List.of(
            new TranslationProviderException(TranslationProviderFailure.TIMEOUT, null),
            new TranslationProviderException(
                TranslationProviderFailure.RATE_LIMITED, Duration.ofSeconds(2)),
            new TranslationProviderException(TranslationProviderFailure.TEMPORARY, null),
            new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null),
            new TranslationProviderException(
                TranslationProviderFailure.BILLING_LIMIT_EXCEEDED, null));

    for (TranslationProviderException failure : failures) {
      Fixture fixture = createQueuedJob(1);
      UUID sourceSegmentId = sourceSegmentIds(unitIds(fixture.jobId()).getFirst()).getFirst();
      String originalSource = readSourceText(sourceSegmentId);
      TranslationUnitLeaseRepository.Lease lease =
          leaseService
              .claimNext("translation-failure-matrix-" + failure.failure(), Duration.ofSeconds(60))
              .orElseThrow();
      TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);

      failureService.record(lease, attempt.attemptId(), failure);

      assertEquals(fixture.revisionId(), readCurrentSourceRevisionId(fixture.chapterId()));
      assertEquals(originalSource, readSourceText(sourceSegmentId));
      assertEquals(0, countCandidates(fixture.jobId()));
      assertEquals(0, countCandidateSegmentsForJob(fixture.jobId()));
      assertEquals(0, countCurrentTranslationVersions(fixture.chapterId()));
      assertEquals(0, countCandidateReadyEvents(fixture.jobId()));
      assertEquals(0, countUsageRows(fixture.jobId()));
      assertTrue(outboxPayloadsContainIdentifiersOnly(fixture.jobId()));
    }
  }

  @Test
  void expiredLeaseCannotCommitAnAttemptFailure() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService
            .claimNext("translation-fake-stale-failure", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);
    expireLease(lease.unitId());

    assertThrows(
        TranslationAttemptFailureRepository.StaleLeaseException.class,
        () ->
            failureService.record(
                lease,
                attempt.attemptId(),
                new TranslationProviderException(TranslationProviderFailure.TIMEOUT, null)));

    assertEquals("running", readAttemptOutcome(attempt.attemptId()));
    assertEquals("running", readUnitStatus(lease.unitId()));
    assertEquals(0, countOutboxEvents(fixture.jobId(), "translation.unit.retry_scheduled"));
    assertEquals(0, countOutboxEvents(fixture.jobId(), "translation.unit.failed"));
  }

  @Test
  void jobFailsWhenItsLastPendingUnitSucceedsAfterAnotherUnitFailed() throws Exception {
    Fixture fixture = createQueuedJob(101);
    TranslationUnitLeaseRepository.Lease failedLease =
        leaseService
            .claimNext("translation-fake-partial-failure", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult failedAttempt = attemptService.begin(failedLease);
    failureService.record(
        failedLease,
        failedAttempt.attemptId(),
        new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null));

    TranslationUnitLeaseRepository.Lease successLease =
        leaseService
            .claimNext("translation-fake-partial-success", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult successAttempt = attemptService.begin(successLease);
    TranslationAttemptCompletionRepository.CompletionResult completion =
        completionService.complete(
            successLease, response(successAttempt.attemptId(), successLease.unitId()));

    assertEquals("failed", completion.jobStatus());
    assertEquals("partial", completion.candidateStatus());
    assertEquals("failed", readJobStatus(fixture.jobId()));
    assertEquals("INVALID_RESPONSE", readJobFailureCode(fixture.jobId()));
    assertJobProgress(fixture.jobId(), "failed", 1, 1, true);
    assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.job.failed"));
  }

  @Test
  void workspaceOwnerAndEditorCanRetryWithIdempotentReceipt() throws Exception {
    Fixture ownerFixture = createFailedSingleUnitJob();
    createWorkspace(ownerFixture);
    TranslationJobRetryRepository.RetryResult ownerRetry =
        jobRetryService.retryFailedUnits(
            ownerFixture.userId(), ownerFixture.jobId(), "workspace-owner-retry-0001");

    assertFalse(ownerRetry.replayed());
    assertEquals(1, ownerRetry.retriedUnits());
    assertEquals("queued", ownerRetry.jobStatus());
    assertEquals("queued", readUnitStatus(unitIds(ownerFixture.jobId()).getFirst()));
    assertRetryEventContainsOnlyIds(
        ownerFixture.jobId(),
        ownerRetry.outboxEventId(),
        unitIds(ownerFixture.jobId()).getFirst(),
        "workspace-owner-retry-0001");

    TranslationJobRetryRepository.RetryResult ownerReplay =
        jobRetryService.retryFailedUnits(
            ownerFixture.userId(), ownerFixture.jobId(), "workspace-owner-retry-0001");
    assertTrue(ownerReplay.replayed());
    assertEquals(ownerRetry.outboxEventId(), ownerReplay.outboxEventId());
    assertEquals(1, countOutboxEvents(ownerFixture.jobId(), "translation.job.retry_requested"));
    assertEquals(
        "review_required",
        fakeLocalWorkerService
            .processNext("translation-fake-retry-worker", Duration.ofSeconds(60))
            .orElseThrow()
            .completion()
            .jobStatus());

    Fixture editorFixture = createFailedSingleUnitJob();
    UUID editorWorkspace = createWorkspace(editorFixture);
    UUID editorId = addWorkspaceMember(editorWorkspace, "editor");
    TranslationJobRetryRepository.RetryResult editorRetry =
        jobRetryService.retryFailedUnits(
            editorId, editorFixture.jobId(), "workspace-editor-retry-0001");
    assertEquals(1, editorRetry.retriedUnits());
    assertEquals("queued", readJobStatus(editorFixture.jobId()));
  }

  @Test
  void workspaceReaderAndOutsideActorCannotRetryAnotherMembersJob() throws Exception {
    Fixture readerFixture = createFailedSingleUnitJob();
    UUID readerWorkspace = createWorkspace(readerFixture);
    UUID readerId = addWorkspaceMember(readerWorkspace, "reader");
    assertThrows(
        TranslationJobRetryRepository.JobNotFoundException.class,
        () ->
            jobRetryService.retryFailedUnits(
                readerId, readerFixture.jobId(), "workspace-reader-retry-0001"));
    assertEquals(0, countOutboxEvents(readerFixture.jobId(), "translation.job.retry_requested"));
    assertEquals("failed", readJobStatus(readerFixture.jobId()));

    Fixture outsiderFixture = createFailedSingleUnitJob();
    createWorkspace(outsiderFixture);
    UUID outsiderId = createUser("outside translation retry fixture");
    assertThrows(
        TranslationJobRetryRepository.JobNotFoundException.class,
        () ->
            jobRetryService.retryFailedUnits(
                outsiderId, outsiderFixture.jobId(), "workspace-outsider-retry-0001"));
    assertEquals(0, countOutboxEvents(outsiderFixture.jobId(), "translation.job.retry_requested"));
    assertEquals("failed", readJobStatus(outsiderFixture.jobId()));
  }

  @Test
  void retryRejectsStaleSourceAndUnitsAtTheirAttemptCeiling() throws Exception {
    Fixture staleFixture = createFailedSingleUnitJob();
    advanceCurrentSourceRevision(staleFixture);
    assertThrows(
        TranslationJobRetryRepository.SourceRevisionStaleException.class,
        () ->
            jobRetryService.retryFailedUnits(
                staleFixture.userId(), staleFixture.jobId(), "stale-source-retry-0001"));
    assertEquals("failed", readJobStatus(staleFixture.jobId()));
    assertEquals(0, countOutboxEvents(staleFixture.jobId(), "translation.job.retry_requested"));

    Fixture exhaustedFixture = createFailedSingleUnitJob();
    setUnitMaxAttempts(unitIds(exhaustedFixture.jobId()).getFirst(), 1);
    assertThrows(
        TranslationJobRetryRepository.NoRetryableUnitsException.class,
        () ->
            jobRetryService.retryFailedUnits(
                exhaustedFixture.userId(), exhaustedFixture.jobId(), "attempt-ceiling-retry-001"));
    assertEquals("failed", readJobStatus(exhaustedFixture.jobId()));
    assertEquals(0, countOutboxEvents(exhaustedFixture.jobId(), "translation.job.retry_requested"));
  }

  @Test
  void queuedJobCanBeCancelledWithAnIdempotentIdentifierOnlyReceipt() throws Exception {
    Fixture fixture = createQueuedJob(1);
    UUID unitId = unitIds(fixture.jobId()).getFirst();
    String idempotencyKey = "00000000-0000-4000-8000-000000000006";

    TranslationJobCancelRepository.CancelResult cancelled =
        jobCancelService.cancel(fixture.userId(), fixture.jobId(), idempotencyKey);

    assertFalse(cancelled.replayed());
    assertEquals(1, cancelled.cancelledUnits());
    assertEquals(0, cancelled.cancelledAttempts());
    assertEquals("cancelled", cancelled.jobStatus());
    assertEquals("cancelled", readJobStatus(fixture.jobId()));
    assertEquals("cancelled", readUnitStatus(unitId));
    assertCancelEventContainsOnlyIds(
        fixture.jobId(), cancelled.outboxEventId(), unitId, null, idempotencyKey);

    TranslationJobCancelRepository.CancelResult replay =
        jobCancelService.cancel(fixture.userId(), fixture.jobId(), idempotencyKey);
    assertTrue(replay.replayed());
    assertEquals(cancelled.outboxEventId(), replay.outboxEventId());
    assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.job.cancel_requested"));
    assertThrows(
        TranslationJobCancelRepository.CancelNotAllowedException.class,
        () -> jobCancelService.cancel(fixture.userId(), fixture.jobId(), "cancel-key-new-0002"));
    assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.job.cancel_requested"));
  }

  @Test
  void cancellingAnActiveFakeAttemptFencesLateCompletionWithoutCandidateWrites() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService
            .claimNext("translation-cancel-active-worker", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);

    TranslationJobCancelRepository.CancelResult cancelled =
        jobCancelService.cancel(
            fixture.userId(), fixture.jobId(), "translation-cancel-running-0001");

    assertEquals(1, cancelled.cancelledUnits());
    assertEquals(1, cancelled.cancelledAttempts());
    assertEquals("cancelled", readUnitStatus(lease.unitId()));
    assertEquals("cancelled", readAttemptOutcome(attempt.attemptId()));
    assertEquals("CANCELLED", readAttemptFailureCode(attempt.attemptId()));
    assertEquals(0, countCandidates(fixture.jobId()));
    assertEquals(0, countCandidateSegmentsForJob(fixture.jobId()));
    assertThrows(
        TranslationAttemptCompletionRepository.StaleLeaseException.class,
        () -> completionService.complete(lease, response(attempt.attemptId(), lease.unitId())));
    assertEquals("cancelled", readJobStatus(fixture.jobId()));
    assertEquals(0, countCandidates(fixture.jobId()));
    assertEquals(0, countUnitCompletedEvents(fixture.jobId()));
    assertEquals(0, countCandidateReadyEvents(fixture.jobId()));
    assertCancelEventContainsOnlyIds(
        fixture.jobId(),
        cancelled.outboxEventId(),
        lease.unitId(),
        attempt.attemptId(),
        "translation-cancel-running-0001");
  }

  @Test
  void writableWorkspaceEditorCanCancelButReaderAndOutsideActorCannot() throws Exception {
    Fixture editorFixture = createQueuedJob(1);
    UUID editorWorkspace = createWorkspace(editorFixture);
    UUID editorId = addWorkspaceMember(editorWorkspace, "editor");
    TranslationJobCancelRepository.CancelResult editorCancel =
        jobCancelService.cancel(editorId, editorFixture.jobId(), "workspace-editor-cancel-0001");
    assertEquals("cancelled", editorCancel.jobStatus());

    Fixture readerFixture = createQueuedJob(1);
    UUID readerWorkspace = createWorkspace(readerFixture);
    UUID readerId = addWorkspaceMember(readerWorkspace, "reader");
    assertThrows(
        TranslationJobCancelRepository.JobNotFoundException.class,
        () ->
            jobCancelService.cancel(
                readerId, readerFixture.jobId(), "workspace-reader-cancel-0001"));
    assertEquals("queued", readJobStatus(readerFixture.jobId()));
    assertEquals(0, countOutboxEvents(readerFixture.jobId(), "translation.job.cancel_requested"));

    Fixture outsiderFixture = createQueuedJob(1);
    createWorkspace(outsiderFixture);
    UUID outsiderId = createUser("outside translation cancel fixture");
    assertThrows(
        TranslationJobCancelRepository.JobNotFoundException.class,
        () ->
            jobCancelService.cancel(
                outsiderId, outsiderFixture.jobId(), "workspace-outsider-cancel-0001"));
    assertEquals("queued", readJobStatus(outsiderFixture.jobId()));
    assertEquals(0, countOutboxEvents(outsiderFixture.jobId(), "translation.job.cancel_requested"));
  }

  @Test
  void cancellationRacingWithCompletionHasOneAtomicWinner() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService
            .claimNext("translation-cancel-race-worker", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> cancelOutcome =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("cancel barrier timed out");
                }
                try {
                  return jobCancelService.cancel(
                      fixture.userId(), fixture.jobId(), "translation-cancel-race-0001");
                } catch (RuntimeException exception) {
                  return exception;
                }
              });
      Future<Object> completionOutcome =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("completion barrier timed out");
                }
                try {
                  return completionService.complete(
                      lease, response(attempt.attemptId(), lease.unitId()));
                } catch (RuntimeException exception) {
                  return exception;
                }
              });

      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      Object cancel = cancelOutcome.get(10, TimeUnit.SECONDS);
      Object completion = completionOutcome.get(10, TimeUnit.SECONDS);
      boolean cancellationWon = cancel instanceof TranslationJobCancelRepository.CancelResult;
      boolean completionWon =
          completion instanceof TranslationAttemptCompletionRepository.CompletionResult;
      assertTrue(cancellationWon ^ completionWon);

      if (cancellationWon) {
        assertTrue(
            completion instanceof TranslationAttemptCompletionRepository.StaleLeaseException);
        assertEquals("cancelled", readJobStatus(fixture.jobId()));
        assertEquals("cancelled", readUnitStatus(lease.unitId()));
        assertEquals("cancelled", readAttemptOutcome(attempt.attemptId()));
        assertEquals(0, countCandidateSegmentsForJob(fixture.jobId()));
        assertEquals(1, countOutboxEvents(fixture.jobId(), "translation.job.cancel_requested"));
        assertEquals(0, countUnitCompletedEvents(fixture.jobId()));
      } else {
        assertTrue(cancel instanceof TranslationJobCancelRepository.CancelNotAllowedException);
        assertEquals("review_required", readJobStatus(fixture.jobId()));
        assertEquals("succeeded", readUnitStatus(lease.unitId()));
        assertEquals("succeeded", readAttemptOutcome(attempt.attemptId()));
        assertEquals(1, countCandidateSegmentsForJob(fixture.jobId()));
        assertEquals(0, countOutboxEvents(fixture.jobId(), "translation.job.cancel_requested"));
        assertEquals(1, countUnitCompletedEvents(fixture.jobId()));
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void cancellationRejectsFailedOrNonFakeLocalJobsWithoutAnEvent() throws Exception {
    Fixture failedFixture = createFailedSingleUnitJob();
    assertThrows(
        TranslationJobCancelRepository.CancelNotAllowedException.class,
        () ->
            jobCancelService.cancel(
                failedFixture.userId(), failedFixture.jobId(), "translation-cancel-failed-0001"));
    assertEquals(0, countOutboxEvents(failedFixture.jobId(), "translation.job.cancel_requested"));

    Fixture nonFakeFixture = createQueuedJob(1);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE translation_jobs SET provider_id = 'external' WHERE id = ?")) {
      update.setObject(1, nonFakeFixture.jobId());
      assertEquals(1, update.executeUpdate());
    }
    assertThrows(
        TranslationJobCancelRepository.CancelNotAllowedException.class,
        () ->
            jobCancelService.cancel(
                nonFakeFixture.userId(),
                nonFakeFixture.jobId(),
                "translation-cancel-external-001"));
    assertEquals("queued", readJobStatus(nonFakeFixture.jobId()));
    assertEquals(0, countOutboxEvents(nonFakeFixture.jobId(), "translation.job.cancel_requested"));
  }

  @Test
  void visibleJobStatusReportsOnlyProgressMetadataAndHidesInaccessibleJobs() throws Exception {
    // The planner batches up to 100 short segments into one unit; exceed that limit to
    // exercise an observable partial-progress state between worker completions.
    Fixture fixture = createQueuedJob(101);

    TranslationJobStatusService.StatusResult queued =
        jobStatusService.findVisible(fixture.userId(), fixture.jobId()).orElseThrow();
    assertEquals("queued", queued.job().status());
    assertTrue(queued.job().sourceRevisionCurrent());
    assertEquals("Synthetic translation source", queued.job().workTitle());
    assertEquals("Chapter 1", queued.job().chapterTitle());
    assertEquals(1, queued.job().chapterOrdinal());
    assertTrue(queued.job().canManage());
    assertEquals(2, queued.job().totalUnits());
    assertEquals(2, queued.job().queuedUnits());
    assertEquals(0, queued.progressPercent());
    assertNull(queued.job().candidateId());
    assertTrue(
        jobStatusService
            .findVisible(createUser("outside translation status fixture"), fixture.jobId())
            .isEmpty());

    fakeLocalWorkerService.processNext("translation-status-worker", Duration.ofSeconds(60));
    TranslationJobStatusService.StatusResult partial =
        jobStatusService.findVisible(fixture.userId(), fixture.jobId()).orElseThrow();
    assertEquals("translating", partial.job().status());
    assertEquals(1, partial.job().completedUnits());
    assertEquals(1, partial.job().queuedUnits());
    assertEquals(50, partial.progressPercent());

    fakeLocalWorkerService.processNext("translation-status-worker", Duration.ofSeconds(60));
    TranslationJobStatusService.StatusResult review =
        jobStatusService.findVisible(fixture.userId(), fixture.jobId()).orElseThrow();
    assertEquals("review_required", review.job().status());
    assertEquals("ready_for_review", review.job().candidateStatus());
    assertEquals(100, review.progressPercent());
    assertEquals(0, review.job().runningUnits());
  }

  @Test
  void visibleJobStatusMarksAnOldSourceRevisionAsStale() throws Exception {
    Fixture fixture = createQueuedJob(1);
    UUID currentRevisionId = advanceCurrentSourceRevision(fixture);

    TranslationJobStatusService.StatusResult stale =
        jobStatusService.findVisible(fixture.userId(), fixture.jobId()).orElseThrow();

    assertFalse(stale.job().sourceRevisionCurrent());
    assertTrue(stale.job().canManage());
    assertNotEquals(stale.job().sourceRevisionId(), currentRevisionId);
  }

  @Test
  void readyFakeCandidatePreviewReturnsOnlyTheRequestedOrderedPageToTheWriter() throws Exception {
    Fixture fixture = createQueuedJob(3);
    fakeLocalWorkerService.processNext("translation-preview-worker", Duration.ofSeconds(60));

    TranslationCandidatePreviewService.CandidatePreview preview =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 1, 1)
            .orElseThrow();

    assertEquals(fixture.jobId(), preview.jobId());
    assertEquals(fixture.revisionId(), preview.sourceRevisionId());
    assertTrue(preview.sourceRevisionCurrent());
    assertEquals("fake-local", preview.providerId());
    assertEquals("ready_for_review", preview.candidateStatus());
    assertEquals(3, preview.totalSegments());
    assertEquals(1, preview.pageOffset());
    assertEquals(1, preview.segments().size());
    assertEquals(2, preview.segments().getFirst().ordinal());
    assertEquals("Synthetic source sentence 2.", preview.segments().getFirst().sourceText());
    assertEquals(
        "[fake vi->en] Synthetic source sentence 2.",
        preview.segments().getFirst().fakeResultText());
  }

  @Test
  void candidateSegmentEditsAreAppendOnlyAndBoundToTheirSourceCandidate() throws Exception {
    Fixture fixture = createQueuedJob(1);
    fakeLocalWorkerService.processNext("translation-edit-storage-worker", Duration.ofSeconds(60));

    TranslationCandidatePreviewService.CandidatePreview preview =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 0, 1)
            .orElseThrow();
    UUID candidateId = preview.candidateId();
    UUID sourceSegmentId = preview.segments().getFirst().sourceSegmentId();
    String editedText = "Bản dịch do người viết chỉnh sửa.";
    byte[] editedTextHash = sha256(editedText);
    byte[] idempotencyKeyHash = sha256("translation-edit-key-" + UUID.randomUUID());
    byte[] requestHash = sha256("request-body-" + UUID.randomUUID());

    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO translation_candidate_segment_edits "
                    + "(candidate_id, source_segment_id, edit_revision, editor_user_id, "
                    + "translated_text, translated_sha256, idempotency_key_sha256, request_sha256) "
                    + "VALUES (?, ?, 1, ?, ?, ?, ?, ?)")) {
      insert.setObject(1, candidateId);
      insert.setObject(2, sourceSegmentId);
      insert.setObject(3, fixture.userId());
      insert.setString(4, editedText);
      insert.setBytes(5, editedTextHash);
      insert.setBytes(6, idempotencyKeyHash);
      insert.setBytes(7, requestHash);
      assertEquals(1, insert.executeUpdate());
    }

    SQLException updateFailure =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection connection = dataSource.getConnection();
                  PreparedStatement update =
                      connection.prepareStatement(
                          "UPDATE translation_candidate_segment_edits "
                              + "SET translated_text = 'overwritten' "
                              + "WHERE candidate_id = ? AND source_segment_id = ?")) {
                update.setObject(1, candidateId);
                update.setObject(2, sourceSegmentId);
                update.executeUpdate();
              }
            });
    assertEquals("55000", updateFailure.getSQLState());

    SQLException deleteFailure =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection connection = dataSource.getConnection();
                  PreparedStatement delete =
                      connection.prepareStatement(
                          "DELETE FROM translation_candidate_segment_edits "
                              + "WHERE candidate_id = ? AND source_segment_id = ?")) {
                delete.setObject(1, candidateId);
                delete.setObject(2, sourceSegmentId);
                delete.executeUpdate();
              }
            });
    assertEquals("55000", deleteFailure.getSQLState());

    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT edit_revision, editor_user_id, translated_text, translated_sha256 "
                    + "FROM translation_candidate_segment_edits "
                    + "WHERE candidate_id = ? AND source_segment_id = ?")) {
      select.setObject(1, candidateId);
      select.setObject(2, sourceSegmentId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        assertEquals(1, row.getInt("edit_revision"));
        assertEquals(fixture.userId(), row.getObject("editor_user_id", UUID.class));
        assertEquals(editedText, row.getString("translated_text"));
        assertArrayEquals(editedTextHash, row.getBytes("translated_sha256"));
        assertFalse(row.next());
      }
    }
  }

  @Test
  void candidateEditsReplayExactlyFenceStaleWritersAndAppearAsAnOverlay() throws Exception {
    Fixture fixture = createQueuedJob(1);
    fakeLocalWorkerService.processNext("translation-edit-api-worker", Duration.ofSeconds(60));
    TranslationCandidatePreviewService.PreviewSegment original =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 0, 1)
            .orElseThrow()
            .segments()
            .getFirst();
    String originalFakeText = original.fakeResultText();
    String firstText = "First writer correction.";
    String firstKey = "translation-edit-api-key-0001";

    TranslationCandidateEditRepository.EditResult first =
        candidateEditService.save(
            fixture.userId(), fixture.jobId(), original.sourceSegmentId(), 0, firstKey, firstText);
    TranslationCandidateEditRepository.EditResult replay =
        candidateEditService.save(
            fixture.userId(), fixture.jobId(), original.sourceSegmentId(), 0, firstKey, firstText);

    assertEquals(1, first.editRevision());
    assertFalse(first.replayed());
    assertTrue(replay.replayed());
    assertEquals(first.editRevision(), replay.editRevision());
    assertEquals(first.createdAt(), replay.createdAt());
    assertThrows(
        TranslationCandidateEditRepository.IdempotencyKeyReusedException.class,
        () ->
            candidateEditService.save(
                fixture.userId(),
                fixture.jobId(),
                original.sourceSegmentId(),
                0,
                firstKey,
                "Different correction."));

    TranslationCandidateEditRepository.EditRevisionConflictException stale =
        assertThrows(
            TranslationCandidateEditRepository.EditRevisionConflictException.class,
            () ->
                candidateEditService.save(
                    fixture.userId(),
                    fixture.jobId(),
                    original.sourceSegmentId(),
                    0,
                    "translation-edit-api-key-0002",
                    "Stale writer correction."));
    assertEquals(1, stale.currentEditRevision());

    TranslationCandidateEditRepository.EditResult second =
        candidateEditService.save(
            fixture.userId(),
            fixture.jobId(),
            original.sourceSegmentId(),
            1,
            "translation-edit-api-key-0003",
            "Second writer correction.");
    TranslationCandidatePreviewService.PreviewSegment latest =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 0, 1)
            .orElseThrow()
            .segments()
            .getFirst();

    assertEquals(2, second.editRevision());
    assertEquals(originalFakeText, latest.fakeResultText());
    assertEquals("Second writer correction.", latest.editedText());
    assertEquals(2, latest.editRevision());
    assertEquals("\"edit-2\"", latest.editEtag());
  }

  @Test
  void concurrentCandidateSegmentWritersProduceOneWinnerAndOneStaleConflict() throws Exception {
    Fixture fixture = createQueuedJob(1);
    fakeLocalWorkerService.processNext(
        "translation-edit-concurrent-worker", Duration.ofSeconds(60));
    UUID segmentId =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 0, 1)
            .orElseThrow()
            .segments()
            .getFirst()
            .sourceSegmentId();
    CountDownLatch writersReady = new CountDownLatch(2);
    CountDownLatch startWriters = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<TranslationCandidateEditRepository.EditResult> first =
          executor.submit(
              () ->
                  saveConcurrentEdit(
                      fixture,
                      segmentId,
                      "translation-edit-race-key-0001",
                      "Writer one.",
                      writersReady,
                      startWriters));
      Future<TranslationCandidateEditRepository.EditResult> second =
          executor.submit(
              () ->
                  saveConcurrentEdit(
                      fixture,
                      segmentId,
                      "translation-edit-race-key-0002",
                      "Writer two.",
                      writersReady,
                      startWriters));
      boolean bothWritersReady = writersReady.await(10, TimeUnit.SECONDS);
      startWriters.countDown();
      assertTrue(bothWritersReady);

      int saved = 0;
      int stale = 0;
      for (Future<TranslationCandidateEditRepository.EditResult> writer : List.of(first, second)) {
        try {
          writer.get(10, TimeUnit.SECONDS);
          saved++;
        } catch (java.util.concurrent.ExecutionException exception) {
          assertTrue(
              exception.getCause()
                  instanceof TranslationCandidateEditRepository.EditRevisionConflictException);
          stale++;
        }
      }
      assertEquals(1, saved);
      assertEquals(1, stale);
    }

    TranslationCandidatePreviewService.PreviewSegment latest =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 0, 1)
            .orElseThrow()
            .segments()
            .getFirst();
    assertEquals(1, latest.editRevision());
    assertTrue(List.of("Writer one.", "Writer two.").contains(latest.editedText()));
  }

  @Test
  void translationDeskPagesOnlyCurrentWorkspaceWritersAndBindsCursorToActor() throws Exception {
    Fixture fixture = createQueuedJob(1);
    UUID secondJobId =
        jobService
            .enqueue(
                fixture.userId(),
                fixture.chapterId(),
                fixture.revisionId(),
                "en",
                "desk-job-key-" + UUID.randomUUID().toString().replace("-", ""))
            .job()
            .id();
    additionalJobsByFixture
        .computeIfAbsent(fixture.jobId(), ignored -> new ArrayList<>())
        .add(secondJobId);
    UUID workspaceId = createWorkspace(fixture);
    UUID editorId = addWorkspaceMember(workspaceId, "editor");
    UUID readerId = addWorkspaceMember(workspaceId, "reader");
    UUID outsiderId = createUser("Translation desk outsider fixture");

    TranslationJobStatusService.JobPage firstPage =
        jobStatusService.listManageable(editorId, null, 1);
    assertEquals(1, firstPage.items().size());
    assertTrue(firstPage.nextCursor() != null);
    TranslationJobStatusService.JobPage secondPage =
        jobStatusService.listManageable(editorId, firstPage.nextCursor(), 1);

    assertEquals(1, secondPage.items().size());
    assertNull(secondPage.nextCursor());
    Set<UUID> listedJobIds = new HashSet<>();
    listedJobIds.add(firstPage.items().getFirst().job().id());
    listedJobIds.add(secondPage.items().getFirst().job().id());
    assertEquals(Set.of(fixture.jobId(), secondJobId), listedJobIds);
    assertTrue(
        jobStatusService.listManageable(fixture.userId(), null, 10).items().stream()
            .allMatch(item -> item.job().canManage()));
    assertTrue(jobStatusService.listManageable(readerId, null, 10).items().isEmpty());
    assertTrue(jobStatusService.listManageable(outsiderId, null, 10).items().isEmpty());
    assertThrows(
        com.novelweb.translation.jobs.TranslationJobCursorCodec.InvalidCursorException.class,
        () -> jobStatusService.listManageable(fixture.userId(), firstPage.nextCursor(), 1));
  }

  @Test
  void candidatePreviewAndEditsConcealReadOnlyWritersAndRejectStaleSources() throws Exception {
    Fixture readOnlyFixture = createQueuedJob(1);
    UUID workspaceId = createWorkspace(readOnlyFixture);
    UUID readerId = addWorkspaceMember(workspaceId, "reader");
    fakeLocalWorkerService.processNext("translation-preview-reader-worker", Duration.ofSeconds(60));

    assertTrue(
        jobStatusService.findVisible(readerId, readOnlyFixture.jobId()).isPresent(),
        "a workspace reader can read progress metadata");
    assertTrue(
        candidatePreviewService
            .findCurrentReadyForReview(readerId, readOnlyFixture.jobId(), 0, 20)
            .isEmpty(),
        "candidate text is withheld from read-only workspace members");
    UUID segmentId =
        candidatePreviewService
            .findCurrentReadyForReview(readOnlyFixture.userId(), readOnlyFixture.jobId(), 0, 1)
            .orElseThrow()
            .segments()
            .getFirst()
            .sourceSegmentId();
    assertThrows(
        TranslationCandidateEditRepository.CandidateNotFoundException.class,
        () ->
            candidateEditService.save(
                readerId,
                readOnlyFixture.jobId(),
                segmentId,
                0,
                "translation-edit-reader-key-01",
                "Unauthorized correction."));

    Fixture staleFixture = createQueuedJob(1);
    fakeLocalWorkerService.processNext("translation-preview-stale-worker", Duration.ofSeconds(60));
    UUID staleSegmentId =
        candidatePreviewService
            .findCurrentReadyForReview(staleFixture.userId(), staleFixture.jobId(), 0, 1)
            .orElseThrow()
            .segments()
            .getFirst()
            .sourceSegmentId();
    advanceCurrentSourceRevision(staleFixture);

    assertThrows(
        TranslationCandidatePreviewRepository.SourceRevisionStaleException.class,
        () ->
            candidatePreviewService.findCurrentReadyForReview(
                staleFixture.userId(), staleFixture.jobId(), 0, 20));
    assertThrows(
        TranslationCandidateEditRepository.SourceRevisionStaleException.class,
        () ->
            candidateEditService.save(
                staleFixture.userId(),
                staleFixture.jobId(),
                staleSegmentId,
                0,
                "translation-edit-stale-key-01",
                "Stale source correction."));

    MvcResult stalePreviewResponse = requestCandidatePreview(staleFixture);
    assertEquals(409, stalePreviewResponse.getResponse().getStatus());
    assertTrue(
        stalePreviewResponse
            .getResponse()
            .getContentAsString()
            .contains("TRANSLATION_SOURCE_STALE"));
    assertTrue(stalePreviewResponse.getResponse().getHeader("Cache-Control").contains("no-store"));
    assertFalse(
        stalePreviewResponse
            .getResponse()
            .getContentAsString()
            .contains("Synthetic source sentence 1."));
    assertFalse(
        stalePreviewResponse
            .getResponse()
            .getContentAsString()
            .contains("[fake vi->en] Synthetic source sentence 1."));
  }

  @Test
  void candidatePreviewDoesNotExposeAnIncompleteCandidate() throws Exception {
    Fixture fixture = createQueuedJob(101);
    fakeLocalWorkerService.processNext(
        "translation-preview-partial-worker", Duration.ofSeconds(60));

    assertThrows(
        TranslationCandidatePreviewRepository.CandidateNotReadyException.class,
        () ->
            candidatePreviewService.findCurrentReadyForReview(
                fixture.userId(), fixture.jobId(), 0, 20));

    MvcResult response = requestCandidatePreview(fixture);
    assertEquals(409, response.getResponse().getStatus());
    String body = response.getResponse().getContentAsString();
    assertTrue(body.contains("TRANSLATION_CANDIDATE_NOT_READY"));
    assertTrue(response.getResponse().getHeader("Cache-Control").contains("no-store"));
    assertFalse(body.contains("Synthetic source sentence 1."));
    assertFalse(body.contains("[fake vi->en] Synthetic source sentence 1."));
  }

  @Test
  void manualCandidateSubmissionFreezesEveryOrderedLatestEditAndRejectsMutation() throws Exception {
    Fixture fixture = createQueuedJob(2);
    fakeLocalWorkerService.processNext(
        "translation-review-snapshot-worker", Duration.ofSeconds(60));
    TranslationCandidatePreviewService.CandidatePreview preview =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 0, 10)
            .orElseThrow();
    List<ReviewSnapshotSegment> snapshotSegments = new ArrayList<>();
    for (TranslationCandidatePreviewService.PreviewSegment segment : preview.segments()) {
      String translatedText = "Bản dịch thủ công đoạn " + segment.ordinal() + ".";
      TranslationCandidateEditRepository.EditResult edit =
          candidateEditService.save(
              fixture.userId(),
              fixture.jobId(),
              segment.sourceSegmentId(),
              0,
              "translation-review-edit-" + UUID.randomUUID().toString().replace("-", ""),
              translatedText);
      snapshotSegments.add(
          new ReviewSnapshotSegment(
              segment.sourceSegmentId(),
              segment.ordinal(),
              edit.editRevision(),
              sha256(edit.translatedText())));
    }

    UUID submissionId =
        insertReviewSnapshot(fixture, preview.candidateId(), snapshotSegments, "complete");

    assertEquals(1, countReviewSubmissions(preview.candidateId()));
    assertEquals(2, countReviewSubmissionSegments(submissionId));
    assertEquals(List.of(1, 2), readReviewSubmissionOrdinals(submissionId));
    assertEquals(
        snapshotSegments.stream()
            .map(segment -> HexFormat.of().formatHex(segment.translatedSha256()))
            .toList(),
        readReviewSubmissionEditHashes(submissionId));
    assertFalse(reviewSnapshotHasTextColumn("translation_candidate_review_submissions"));
    assertFalse(reviewSnapshotHasTextColumn("translation_candidate_review_submission_segments"));

    SQLException updateFailure =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "UPDATE translation_candidate_review_submissions "
                        + "SET snapshot_sha256 = ? WHERE id = ?",
                    sha256("replacement snapshot"),
                    submissionId));
    assertEquals("55000", updateFailure.getSQLState());

    SQLException deleteFailure =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "DELETE FROM translation_candidate_review_submission_segments "
                        + "WHERE submission_id = ?",
                    submissionId));
    assertEquals("55000", deleteFailure.getSQLState());

    SQLException truncateFailure =
        assertThrows(
            SQLException.class,
            () -> execute("TRUNCATE translation_candidate_review_submissions CASCADE"));
    assertEquals("55000", truncateFailure.getSQLState());

    SQLException pendingConflict =
        assertThrows(
            SQLException.class,
            () -> insertReviewSnapshot(fixture, preview.candidateId(), snapshotSegments, "second"));
    assertEquals("23505", pendingConflict.getSQLState());
  }

  @Test
  void manualCandidateSubmissionRejectsIncompleteOrNonlatestEdits() throws Exception {
    Fixture incompleteFixture = createQueuedJob(2);
    fakeLocalWorkerService.processNext(
        "translation-review-incomplete-worker", Duration.ofSeconds(60));
    TranslationCandidatePreviewService.CandidatePreview incompletePreview =
        candidatePreviewService
            .findCurrentReadyForReview(incompleteFixture.userId(), incompleteFixture.jobId(), 0, 10)
            .orElseThrow();
    TranslationCandidatePreviewService.PreviewSegment firstSegment =
        incompletePreview.segments().getFirst();
    TranslationCandidateEditRepository.EditResult firstEdit =
        candidateEditService.save(
            incompleteFixture.userId(),
            incompleteFixture.jobId(),
            firstSegment.sourceSegmentId(),
            0,
            "translation-review-incomplete-" + UUID.randomUUID().toString().replace("-", ""),
            "Only one segment has a manual translation.");
    List<ReviewSnapshotSegment> incompleteSnapshot =
        List.of(
            new ReviewSnapshotSegment(
                firstSegment.sourceSegmentId(),
                firstSegment.ordinal(),
                firstEdit.editRevision(),
                sha256(firstEdit.translatedText())));

    SQLException incompleteFailure =
        assertThrows(
            SQLException.class,
            () ->
                insertReviewSnapshot(
                    incompleteFixture,
                    incompletePreview.candidateId(),
                    incompleteSnapshot,
                    "incomplete"));
    assertEquals("23514", incompleteFailure.getSQLState());
    assertEquals(0, countReviewSubmissions(incompletePreview.candidateId()));

    Fixture staleEditFixture = createQueuedJob(1);
    fakeLocalWorkerService.processNext(
        "translation-review-stale-edit-worker", Duration.ofSeconds(60));
    TranslationCandidatePreviewService.CandidatePreview staleEditPreview =
        candidatePreviewService
            .findCurrentReadyForReview(staleEditFixture.userId(), staleEditFixture.jobId(), 0, 1)
            .orElseThrow();
    UUID segmentId = staleEditPreview.segments().getFirst().sourceSegmentId();
    candidateEditService.save(
        staleEditFixture.userId(),
        staleEditFixture.jobId(),
        segmentId,
        0,
        "translation-review-revision-one-" + UUID.randomUUID().toString().replace("-", ""),
        "First manual revision.");
    TranslationCandidateEditRepository.EditResult latestEdit =
        candidateEditService.save(
            staleEditFixture.userId(),
            staleEditFixture.jobId(),
            segmentId,
            1,
            "translation-review-revision-two-" + UUID.randomUUID().toString().replace("-", ""),
            "Second manual revision.");
    List<ReviewSnapshotSegment> staleEditSnapshot =
        List.of(new ReviewSnapshotSegment(segmentId, 1, 1, sha256("First manual revision.")));

    SQLException staleEditFailure =
        assertThrows(
            SQLException.class,
            () ->
                insertReviewSnapshot(
                    staleEditFixture,
                    staleEditPreview.candidateId(),
                    staleEditSnapshot,
                    "old-edit"));
    assertEquals("23514", staleEditFailure.getSQLState());
    assertEquals(0, countReviewSubmissions(staleEditPreview.candidateId()));
    assertEquals(2, latestEdit.editRevision());
  }

  @Test
  void manualCandidateSubmissionRejectsAStaleSourceRevision() throws Exception {
    Fixture fixture = createQueuedJob(1);
    fakeLocalWorkerService.processNext(
        "translation-review-stale-source-worker", Duration.ofSeconds(60));
    TranslationCandidatePreviewService.CandidatePreview preview =
        candidatePreviewService
            .findCurrentReadyForReview(fixture.userId(), fixture.jobId(), 0, 1)
            .orElseThrow();
    TranslationCandidatePreviewService.PreviewSegment segment = preview.segments().getFirst();
    TranslationCandidateEditRepository.EditResult edit =
        candidateEditService.save(
            fixture.userId(),
            fixture.jobId(),
            segment.sourceSegmentId(),
            0,
            "translation-review-source-edit-" + UUID.randomUUID().toString().replace("-", ""),
            "Manually translated before source change.");
    List<ReviewSnapshotSegment> snapshot =
        List.of(
            new ReviewSnapshotSegment(
                segment.sourceSegmentId(),
                segment.ordinal(),
                edit.editRevision(),
                sha256(edit.translatedText())));
    advanceCurrentSourceRevision(fixture);

    SQLException staleFailure =
        assertThrows(
            SQLException.class,
            () -> insertReviewSnapshot(fixture, preview.candidateId(), snapshot, "stale-source"));
    assertEquals("23514", staleFailure.getSQLState());
    assertEquals(0, countReviewSubmissions(preview.candidateId()));
  }

  private UUID insertReviewSnapshot(
      Fixture fixture,
      UUID candidateId,
      List<ReviewSnapshotSegment> snapshotSegments,
      String snapshotToken)
      throws Exception {
    UUID submissionId = UUID.randomUUID();
    byte[] sourceSha256 = readCandidateSourceSha256(candidateId);
    byte[] snapshotSha256 = sha256("translation-review-snapshot-" + snapshotToken);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO translation_candidate_review_submissions
                        (id, candidate_id, job_id, work_id, chapter_id, target_language,
                         source_revision_id, source_sha256, snapshot_sha256, segment_count,
                         submitted_by_user_id, idempotency_key_sha256, request_sha256)
                    VALUES (?, ?, ?, ?, ?, 'en', ?, ?, ?, ?, ?, ?, ?)
                    """)) {
          insert.setObject(1, submissionId);
          insert.setObject(2, candidateId);
          insert.setObject(3, fixture.jobId());
          insert.setObject(4, fixture.workId());
          insert.setObject(5, fixture.chapterId());
          insert.setObject(6, fixture.revisionId());
          insert.setBytes(7, sourceSha256);
          insert.setBytes(8, snapshotSha256);
          insert.setInt(9, snapshotSegments.size());
          insert.setObject(10, fixture.userId());
          insert.setBytes(11, sha256("translation-review-key-" + snapshotToken));
          insert.setBytes(12, sha256("translation-review-request-" + snapshotToken));
          assertEquals(1, insert.executeUpdate());
        }
        try (PreparedStatement insertSegment =
            connection.prepareStatement(
                """
                    INSERT INTO translation_candidate_review_submission_segments
                        (submission_id, candidate_id, source_segment_id, segment_ordinal,
                         edit_revision, translated_sha256)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
          for (ReviewSnapshotSegment segment : snapshotSegments) {
            insertSegment.setObject(1, submissionId);
            insertSegment.setObject(2, candidateId);
            insertSegment.setObject(3, segment.sourceSegmentId());
            insertSegment.setInt(4, segment.ordinal());
            insertSegment.setInt(5, segment.editRevision());
            insertSegment.setBytes(6, segment.translatedSha256());
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
    return submissionId;
  }

  private byte[] readCandidateSourceSha256(UUID candidateId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT source_sha256 FROM translation_candidates WHERE id = ?")) {
      select.setObject(1, candidateId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBytes("source_sha256");
      }
    }
  }

  private int countReviewSubmissions(UUID candidateId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT count(*) FROM translation_candidate_review_submissions "
                    + "WHERE candidate_id = ?")) {
      select.setObject(1, candidateId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private int countReviewSubmissionSegments(UUID submissionId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT count(*) FROM translation_candidate_review_submission_segments "
                    + "WHERE submission_id = ?")) {
      select.setObject(1, submissionId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private List<Integer> readReviewSubmissionOrdinals(UUID submissionId) throws SQLException {
    List<Integer> ordinals = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT segment_ordinal FROM translation_candidate_review_submission_segments "
                    + "WHERE submission_id = ? ORDER BY segment_ordinal")) {
      select.setObject(1, submissionId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) ordinals.add(rows.getInt(1));
      }
    }
    return ordinals;
  }

  private List<String> readReviewSubmissionEditHashes(UUID submissionId) throws SQLException {
    List<String> hashes = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT translated_sha256 FROM translation_candidate_review_submission_segments "
                    + "WHERE submission_id = ? ORDER BY segment_ordinal")) {
      select.setObject(1, submissionId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) hashes.add(HexFormat.of().formatHex(rows.getBytes(1)));
      }
    }
    return hashes;
  }

  private boolean reviewSnapshotHasTextColumn(String tableName) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = ? "
                    + "AND (column_name ILIKE '%text%' OR column_name ILIKE '%body%'))")) {
      select.setString(1, tableName);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBoolean(1);
      }
    }
  }

  private MvcResult requestCandidatePreview(Fixture fixture) throws Exception {
    PolicyService policyService = mock(PolicyService.class);
    when(policyService.requiredAcceptance(fixture.userId()))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    TranslationJobController controller =
        new TranslationJobController(
            jobService,
            jobStatusService,
            jobRetryService,
            jobCancelService,
            candidatePreviewService,
            candidateEditService,
            policyService);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            new SessionService.SessionPrincipal(fixture.userId(), Instant.now().plusSeconds(300)),
            null,
            List.of());

    return mockMvc
        .perform(
            get("/api/v1/translation/jobs/{jobId}/candidate", fixture.jobId())
                .principal(authentication))
        .andReturn();
  }

  private TranslationCandidateEditRepository.EditResult saveConcurrentEdit(
      Fixture fixture,
      UUID segmentId,
      String idempotencyKey,
      String translatedText,
      CountDownLatch writersReady,
      CountDownLatch startWriters)
      throws Exception {
    writersReady.countDown();
    assertTrue(startWriters.await(10, TimeUnit.SECONDS));
    return candidateEditService.save(
        fixture.userId(), fixture.jobId(), segmentId, 0, idempotencyKey, translatedText);
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

  private Fixture createFailedSingleUnitJob() throws Exception {
    Fixture fixture = createQueuedJob(1);
    TranslationUnitLeaseRepository.Lease lease =
        leaseService
            .claimNext("translation-retry-fixture-worker", Duration.ofSeconds(60))
            .orElseThrow();
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);
    failureService.record(
        lease,
        attempt.attemptId(),
        new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null));
    assertEquals("failed", readJobStatus(fixture.jobId()));
    return fixture;
  }

  private UUID createWorkspace(Fixture fixture) throws SQLException {
    UUID workspaceId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(
            connection,
            "INSERT INTO workspaces (id, name, created_by_user_id) VALUES (?, ?, ?)",
            workspaceId,
            "Translation retry workspace",
            fixture.userId());
        execute(
            connection,
            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'owner')",
            workspaceId,
            fixture.userId());
        execute(
            connection,
            "UPDATE libraries SET workspace_id = ?, is_default = FALSE WHERE id = ?",
            workspaceId,
            fixture.libraryId());
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
    workspaceByLibrary.put(fixture.libraryId(), workspaceId);
    return workspaceId;
  }

  private UUID addWorkspaceMember(UUID workspaceId, String role) throws SQLException {
    UUID userId = createUser("Translation retry " + role + " fixture");
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
          workspaceId,
          userId,
          role);
    }
    return userId;
  }

  private UUID createUser(String displayName) throws SQLException {
    UUID userId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection, "INSERT INTO users (id, display_name) VALUES (?, ?)", userId, displayName);
    }
    extraUserFixtures.add(userId);
    return userId;
  }

  private UUID advanceCurrentSourceRevision(Fixture fixture) throws Exception {
    UUID nextRevisionId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(
            connection,
            "INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, "
                + "normalized_content, content_hash, created_by_user_id) "
                + "VALUES (?, ?, 2, 'plain_text', ?, ?, ?)",
            nextRevisionId,
            fixture.chapterId(),
            "New source revision",
            sha256("New source revision"),
            fixture.userId());
        execute(
            connection,
            "UPDATE chapters SET source_revision_id = ? WHERE id = ?",
            nextRevisionId,
            fixture.chapterId());
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
    return nextRevisionId;
  }

  private void setUnitMaxAttempts(UUID unitId, int maxAttempts) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE translation_units SET max_attempts = ? WHERE id = ?")) {
      update.setInt(1, maxAttempts);
      update.setObject(2, unitId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void deleteFixture(Fixture fixture) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        List<UUID> jobIds = new ArrayList<>();
        jobIds.add(fixture.jobId());
        jobIds.addAll(additionalJobsByFixture.getOrDefault(fixture.jobId(), List.of()));
        for (UUID jobId : jobIds) {
          execute(
              connection,
              "DELETE FROM translation_versions WHERE candidate_id IN "
                  + "(SELECT id FROM translation_candidates WHERE job_id = ?)",
              jobId);
          execute(connection, "DELETE FROM translation_candidate_segments WHERE job_id = ?", jobId);
          execute(connection, "DELETE FROM translation_candidates WHERE job_id = ?", jobId);
          execute(connection, "DELETE FROM translation_jobs WHERE id = ?", jobId);
        }
        execute(
            connection,
            "DELETE FROM segments WHERE chapter_revision_id IN "
                + "(SELECT id FROM chapter_revisions WHERE chapter_id = ?)",
            fixture.chapterId());
        execute(
            connection, "DELETE FROM chapter_revisions WHERE chapter_id = ?", fixture.chapterId());
        execute(connection, "DELETE FROM chapters WHERE id = ?", fixture.chapterId());
        execute(connection, "DELETE FROM works WHERE id = ?", fixture.workId());
        execute(connection, "DELETE FROM libraries WHERE id = ?", fixture.libraryId());
        UUID workspaceId = workspaceByLibrary.remove(fixture.libraryId());
        additionalJobsByFixture.remove(fixture.jobId());
        if (workspaceId != null) {
          execute(connection, "DELETE FROM workspaces WHERE id = ?", workspaceId);
        }
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

  private String readJobFailureCode(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT failure_code FROM translation_jobs WHERE id = ?")) {
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

  private TranslationResponse response(UUID attemptId, UUID unitId) throws SQLException {
    List<SegmentResult> translations =
        sourceSegmentIds(unitId).stream()
            .map(id -> new SegmentResult(id, "[fake vi->en] translated " + id))
            .toList();
    return new TranslationResponse(
        attemptId,
        "fake-" + attemptId,
        "fake-local",
        translations,
        0,
        TranslationBillingUnit.CODE_POINT);
  }

  private List<UUID> sourceSegmentIds(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT source_segment_id FROM translation_unit_segments "
                    + "WHERE unit_id = ? ORDER BY segment_ordinal")) {
      select.setObject(1, unitId);
      try (ResultSet rows = select.executeQuery()) {
        List<UUID> ids = new ArrayList<>();
        while (rows.next()) ids.add(rows.getObject(1, UUID.class));
        return List.copyOf(ids);
      }
    }
  }

  private List<UUID> unitIds(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT id FROM translation_units WHERE job_id = ? ORDER BY unit_no")) {
      select.setObject(1, jobId);
      try (ResultSet rows = select.executeQuery()) {
        List<UUID> ids = new ArrayList<>();
        while (rows.next()) ids.add(rows.getObject(1, UUID.class));
        return List.copyOf(ids);
      }
    }
  }

  private long countCandidates(UUID jobId) throws SQLException {
    return countBySql("SELECT COUNT(*) FROM translation_candidates WHERE job_id = ?", jobId);
  }

  private long countCandidateSegments(UUID candidateId) throws SQLException {
    return countBySql(
        "SELECT COUNT(*) FROM translation_candidate_segments WHERE candidate_id = ?", candidateId);
  }

  private long countCandidateSegmentsForJob(UUID jobId) throws SQLException {
    return countBySql(
        "SELECT COUNT(*) FROM translation_candidate_segments WHERE job_id = ?", jobId);
  }

  private long countCurrentTranslationVersions(UUID chapterId) throws SQLException {
    return countBySql(
        "SELECT COUNT(*) FROM translation_versions WHERE chapter_id = ? AND status = 'current'",
        chapterId);
  }

  private long countUsageRows(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT COUNT(*)
                    FROM translation_usage_ledger usage
                    JOIN translation_attempts attempt ON attempt.id = usage.attempt_id
                    JOIN translation_units unit ON unit.id = attempt.unit_id
                    WHERE unit.job_id = ?
                    """)) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private void insertCurrentTranslationVersion(Fixture fixture, UUID candidateId, int versionNo)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          """
              INSERT INTO translation_versions
                  (id, work_id, chapter_id, target_language, source_revision_id,
                   candidate_id, version_no, status, created_by_user_id)
              VALUES (?, ?, ?, 'en', ?, ?, ?, 'current', ?)
              """,
          UUID.randomUUID(),
          fixture.workId(),
          fixture.chapterId(),
          fixture.revisionId(),
          candidateId,
          versionNo,
          fixture.userId());
    }
  }

  private long countUnitCompletedEvents(UUID jobId) throws SQLException {
    return countBySql(
        "SELECT COUNT(*) FROM translation_outbox_events "
            + "WHERE aggregate_id = ? AND event_type = 'translation.unit.completed'",
        jobId);
  }

  private long countCandidateReadyEvents(UUID jobId) throws SQLException {
    return countBySql(
        "SELECT COUNT(*) FROM translation_outbox_events "
            + "WHERE aggregate_id = ? AND event_type = 'translation.candidate.ready'",
        jobId);
  }

  private long countBySql(String sql, UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private int readCompletedUnits(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT completed_units FROM translation_jobs WHERE id = ?")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getInt(1);
      }
    }
  }

  private String readUnitStatus(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT status FROM translation_units WHERE id = ?")) {
      select.setObject(1, unitId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String readAttemptOutcome(UUID attemptId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT outcome FROM translation_attempts WHERE id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String readAttemptFailureCode(UUID attemptId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT failure_code FROM translation_attempts WHERE id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String readUnitFailureCode(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT last_failure_code FROM translation_units WHERE id = ?")) {
      select.setObject(1, unitId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private long countOutboxEvents(UUID jobId, String eventType) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT count(*) FROM translation_outbox_events WHERE aggregate_id = ? "
                    + "AND event_type = ?")) {
      select.setObject(1, jobId);
      select.setString(2, eventType);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private boolean outboxPayloadsContainIdentifiersOnly(UUID jobId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT bool_and((payload - 'jobId' - 'unitId' - 'attemptId' - 'attemptNo')
                                    = '{}'::jsonb)
                    FROM translation_outbox_events
                    WHERE aggregate_id = ?
                      AND event_type IN ('translation.unit.retry_scheduled',
                                         'translation.unit.failed', 'translation.job.failed')
                    """)) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBoolean(1);
      }
    }
  }

  private void assertRetryEventContainsOnlyIds(
      UUID jobId, UUID eventId, UUID unitId, String rawIdempotencyKey) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT event_type,
                           (payload - 'jobId' - 'sourceRevisionId' - 'unitIds') = '{}'::jsonb
                               AS only_ids,
                           payload->>'jobId' AS payload_job_id,
                           jsonb_array_length(payload->'unitIds') AS unit_count,
                           payload->'unitIds' @> jsonb_build_array(?::text) AS has_unit_id,
                           deduplication_key
                    FROM translation_outbox_events
                    WHERE event_id = ? AND aggregate_id = ?
                    """)) {
      select.setString(1, unitId.toString());
      select.setObject(2, eventId);
      select.setObject(3, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        assertEquals("translation.job.retry_requested", row.getString("event_type"));
        assertTrue(row.getBoolean("only_ids"));
        assertEquals(jobId.toString(), row.getString("payload_job_id"));
        assertEquals(1, row.getInt("unit_count"));
        assertTrue(row.getBoolean("has_unit_id"));
        assertFalse(row.getString("deduplication_key").contains(rawIdempotencyKey));
      }
    }
  }

  private void assertCancelEventContainsOnlyIds(
      UUID jobId, UUID eventId, UUID unitId, UUID attemptId, String rawIdempotencyKey)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT event_type,
                           (payload - 'jobId' - 'unitIds' - 'attemptIds') = '{}'::jsonb AS only_ids,
                           payload->>'jobId' AS payload_job_id,
                           jsonb_array_length(payload->'unitIds') AS unit_count,
                           jsonb_array_length(payload->'attemptIds') AS attempt_count,
                           payload->'unitIds' @> jsonb_build_array(?::text) AS has_unit_id,
                           payload->'attemptIds' @> jsonb_build_array(?::text) AS has_attempt_id,
                           deduplication_key
                    FROM translation_outbox_events
                    WHERE event_id = ? AND aggregate_id = ?
                    """)) {
      select.setString(1, unitId == null ? null : unitId.toString());
      select.setString(2, attemptId == null ? null : attemptId.toString());
      select.setObject(3, eventId);
      select.setObject(4, jobId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        assertEquals("translation.job.cancel_requested", row.getString("event_type"));
        assertTrue(row.getBoolean("only_ids"));
        assertEquals(jobId.toString(), row.getString("payload_job_id"));
        assertEquals(unitId == null ? 0 : 1, row.getInt("unit_count"));
        assertEquals(attemptId == null ? 0 : 1, row.getInt("attempt_count"));
        if (unitId != null) assertTrue(row.getBoolean("has_unit_id"));
        if (attemptId != null) assertTrue(row.getBoolean("has_attempt_id"));
        assertFalse(row.getString("deduplication_key").contains(rawIdempotencyKey));
      }
    }
  }

  private void makeUnitAvailableNow(UUID unitId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE translation_units SET available_at = clock_timestamp() WHERE id = ?")) {
      update.setObject(1, unitId);
      assertEquals(1, update.executeUpdate());
    }
  }

  private String readCandidateTranslation(UUID candidateId, UUID sourceSegmentId)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT translated_text FROM translation_candidate_segments "
                    + "WHERE candidate_id = ? AND source_segment_id = ?")) {
      select.setObject(1, candidateId);
      select.setObject(2, sourceSegmentId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String readSourceText(UUID sourceSegmentId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT source_text FROM segments WHERE id = ?")) {
      select.setObject(1, sourceSegmentId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private UUID readCurrentSourceRevisionId(UUID chapterId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement("SELECT source_revision_id FROM chapters WHERE id = ?")) {
      select.setObject(1, chapterId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getObject(1, UUID.class);
      }
    }
  }

  private BigDecimal readAttemptEstimate(UUID attemptId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT estimate_amount FROM translation_attempts WHERE id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBigDecimal(1);
      }
    }
  }

  private long readAttemptBilledUnits(UUID attemptId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT billed_units FROM translation_attempts WHERE id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private long readUsageUnits(UUID attemptId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT confirmed_units FROM translation_usage_ledger WHERE attempt_id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getLong(1);
      }
    }
  }

  private String readUsageBillingUnit(UUID attemptId) throws SQLException {
    return readUsageText(attemptId, "billing_unit");
  }

  private String readUsageProvider(UUID attemptId) throws SQLException {
    return readUsageText(attemptId, "provider_id");
  }

  private String readUsageModel(UUID attemptId) throws SQLException {
    return readUsageText(attemptId, "provider_model");
  }

  private String readUsageRequestId(UUID attemptId) throws SQLException {
    return readUsageText(attemptId, "provider_request_id");
  }

  private String readUsageText(UUID attemptId, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT " + column + " FROM translation_usage_ledger WHERE attempt_id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private String readAttemptEstimateCurrency(UUID attemptId) throws SQLException {
    return readAttemptTextField(attemptId, "estimate_currency");
  }

  private String readAttemptEstimateUnit(UUID attemptId) throws SQLException {
    return readAttemptTextField(attemptId, "estimated_billing_unit");
  }

  private String readAttemptPricingVersion(UUID attemptId) throws SQLException {
    return readAttemptTextField(attemptId, "pricing_version");
  }

  private String readAttemptTextField(UUID attemptId, String field) throws SQLException {
    if (!List.of("estimate_currency", "estimated_billing_unit", "pricing_version")
        .contains(field)) {
      throw new IllegalArgumentException("Unsupported attempt field");
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT " + field + " FROM translation_attempts WHERE id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getString(1);
      }
    }
  }

  private byte[] readAttemptResponseHash(UUID attemptId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT response_sha256 FROM translation_attempts WHERE id = ?")) {
      select.setObject(1, attemptId);
      try (ResultSet row = select.executeQuery()) {
        assertTrue(row.next());
        return row.getBytes(1);
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

  private void execute(String sql, Object... values) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, sql, values);
    }
  }

  private static byte[] sha256(String value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
  }

  private record Fixture(
      UUID userId, UUID libraryId, UUID workId, UUID chapterId, UUID revisionId, UUID jobId) {}

  private record ReviewSnapshotSegment(
      UUID sourceSegmentId, int ordinal, int editRevision, byte[] translatedSha256) {}
}
