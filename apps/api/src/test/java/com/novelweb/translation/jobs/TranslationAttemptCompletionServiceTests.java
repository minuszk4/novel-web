package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelweb.translation.provider.TranslationBillingUnit;
import com.novelweb.translation.provider.TranslationResponse;
import com.novelweb.translation.provider.TranslationResponse.SegmentResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranslationAttemptCompletionServiceTests {

  private TranslationAttemptCompletionRepository repository;
  private TranslationAttemptCompletionService service;

  @BeforeEach
  void setUp() {
    repository = mock(TranslationAttemptCompletionRepository.class);
    service = new TranslationAttemptCompletionService(repository);
  }

  @Test
  void completeRequiresACompleteLeaseAndResponseBeforeRepositoryAccess() {
    assertThrows(NullPointerException.class, () -> service.complete(null, response()));
    assertThrows(
        NullPointerException.class, () -> service.complete(leaseWithNullJob(), response()));
    assertThrows(NullPointerException.class, () -> service.complete(lease(), null));
    assertThrows(IllegalArgumentException.class, () -> service.complete(lease(4, 3), response()));
    verifyNoInteractions(repository);
  }

  @Test
  void completeDelegatesTheLeaseAndValidatedResponse() {
    TranslationUnitLeaseRepository.Lease lease = lease();
    TranslationResponse response = response();
    TranslationAttemptCompletionRepository.CompletionResult result =
        new TranslationAttemptCompletionRepository.CompletionResult(
            response.attemptId(),
            lease.jobId(),
            lease.unitId(),
            UUID.randomUUID(),
            1,
            "ready_for_review",
            "review_required",
            false);
    when(repository.complete(lease, response)).thenReturn(result);

    assertEquals(result, service.complete(lease, response));

    verify(repository).complete(lease, response);
  }

  private static TranslationResponse response() {
    UUID attemptId = UUID.randomUUID();
    return new TranslationResponse(
        attemptId,
        "fake-" + attemptId,
        "fake-local",
        List.of(new SegmentResult(UUID.randomUUID(), "[fake] text")),
        0,
        TranslationBillingUnit.CODE_POINT);
  }

  private static TranslationUnitLeaseRepository.Lease lease() {
    return lease(0, 3);
  }

  private static TranslationUnitLeaseRepository.Lease lease(int attemptCount, int maxAttempts) {
    return new TranslationUnitLeaseRepository.Lease(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        attemptCount,
        maxAttempts,
        "worker-1",
        UUID.randomUUID(),
        Instant.now().plusSeconds(30),
        Instant.now());
  }

  private static TranslationUnitLeaseRepository.Lease leaseWithNullJob() {
    return new TranslationUnitLeaseRepository.Lease(
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        0,
        3,
        "worker-1",
        UUID.randomUUID(),
        Instant.now().plusSeconds(30),
        Instant.now());
  }
}
