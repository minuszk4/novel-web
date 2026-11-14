package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelweb.translation.provider.TranslationLanguagePair;
import com.novelweb.translation.provider.TranslationProviderException;
import com.novelweb.translation.provider.TranslationProviderExecutionGate;
import com.novelweb.translation.provider.TranslationProviderFailure;
import com.novelweb.translation.provider.TranslationRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TranslationFakeLocalWorkerServiceTests {

  @Test
  void recordsSanitizedFailureBeforePropagatingItToThePoller() throws Exception {
    TranslationUnitLeaseService leaseService = Mockito.mock(TranslationUnitLeaseService.class);
    TranslationAttemptWorkRepository workRepository =
        Mockito.mock(TranslationAttemptWorkRepository.class);
    TranslationAttemptService attemptService = Mockito.mock(TranslationAttemptService.class);
    TranslationProviderExecutionGate executionGate =
        Mockito.mock(TranslationProviderExecutionGate.class);
    TranslationAttemptCompletionService completionService =
        Mockito.mock(TranslationAttemptCompletionService.class);
    TranslationAttemptFailureService failureService =
        Mockito.mock(TranslationAttemptFailureService.class);

    UUID jobId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    TranslationUnitLeaseRepository.Lease lease =
        new TranslationUnitLeaseRepository.Lease(
            jobId,
            unitId,
            revisionId,
            1,
            0,
            3,
            "worker-1",
            UUID.randomUUID(),
            Instant.now().plusSeconds(60),
            Instant.now());
    TranslationAttemptRepository.StartResult attempt =
        new TranslationAttemptRepository.StartResult(
            attemptId, jobId, unitId, 1, "fake-local", new byte[32], "running", false, true);
    TranslationProviderException failure =
        new TranslationProviderException(TranslationProviderFailure.TIMEOUT, null);

    when(leaseService.claimNext("worker-1", Duration.ofSeconds(60))).thenReturn(Optional.of(lease));
    when(workRepository.load(lease))
        .thenReturn(
            new TranslationAttemptWorkRepository.WorkSnapshot(
                new TranslationLanguagePair("vi", "en"),
                List.of(new TranslationRequest.Segment(UUID.randomUUID(), "local test source"))));
    when(attemptService.begin(lease)).thenReturn(attempt);
    doThrow(failure).when(executionGate).execute(any(TranslationRequest.class));

    TranslationFakeLocalWorkerService worker =
        new TranslationFakeLocalWorkerService(
            leaseService,
            workRepository,
            attemptService,
            executionGate,
            completionService,
            failureService);

    assertThrows(
        TranslationProviderException.class,
        () -> worker.processNext("worker-1", Duration.ofSeconds(60)));

    verify(failureService).record(lease, attemptId, failure);
    verify(completionService, never()).completeExecution(any(), any());
  }
}
