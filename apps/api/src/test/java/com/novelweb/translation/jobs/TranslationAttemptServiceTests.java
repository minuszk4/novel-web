package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranslationAttemptServiceTests {

  private TranslationAttemptRepository repository;
  private TranslationAttemptService service;

  @BeforeEach
  void setUp() {
    repository = mock(TranslationAttemptRepository.class);
    service = new TranslationAttemptService(repository);
  }

  @Test
  void beginRequiresACompleteCurrentLeaseBeforeRepositoryAccess() {
    assertThrows(NullPointerException.class, () -> service.begin(null));
    assertThrows(
        NullPointerException.class,
        () ->
            service.begin(
                new TranslationUnitLeaseRepository.Lease(
                    null,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    0,
                    3,
                    "worker-1",
                    UUID.randomUUID(),
                    Instant.now().plusSeconds(30),
                    Instant.now())));
    verifyNoInteractions(repository);
  }

  @Test
  void beginDelegatesTheLeaseAndReturnsTheDurableAttemptResult() {
    TranslationUnitLeaseRepository.Lease lease = lease();
    TranslationAttemptRepository.StartResult result =
        new TranslationAttemptRepository.StartResult(
            UUID.randomUUID(),
            lease.jobId(),
            lease.unitId(),
            1,
            "fake-local",
            new byte[32],
            "running",
            false,
            true);
    when(repository.begin(lease)).thenReturn(result);

    assertEquals(result, service.begin(lease));

    verify(repository).begin(lease);
  }

  private static TranslationUnitLeaseRepository.Lease lease() {
    return new TranslationUnitLeaseRepository.Lease(
        UUID.randomUUID(),
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
