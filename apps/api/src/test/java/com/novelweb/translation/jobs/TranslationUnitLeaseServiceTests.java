package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranslationUnitLeaseServiceTests {

  private TranslationUnitLeaseRepository repository;
  private TranslationUnitLeaseService service;

  @BeforeEach
  void setUp() {
    repository = mock(TranslationUnitLeaseRepository.class);
    service = new TranslationUnitLeaseService(repository);
  }

  @Test
  void claimRejectsUnsafeWorkerIdsAndInvalidLeaseDurationsBeforeRepositoryAccess() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.claimNext("worker name", Duration.ofSeconds(30)));
    assertThrows(
        IllegalArgumentException.class, () -> service.claimNext("worker-1", Duration.ofSeconds(4)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.claimNext("worker-1", Duration.ofNanos(5_000_000_001L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.claimNext("worker-1", Duration.ofSeconds(901)));

    verifyNoInteractions(repository);
  }

  @Test
  void claimPassesValidatedWorkerAndLeaseSecondsToRepository() {
    when(repository.claimNext("worker-01.node-a", 60)).thenReturn(Optional.empty());

    service.claimNext("worker-01.node-a", Duration.ofMinutes(1));

    verify(repository).claimNext("worker-01.node-a", 60);
  }

  @Test
  void heartbeatUsesTheCurrentClaimIdentityAndValidatedExtension() {
    UUID jobId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    TranslationUnitLeaseRepository.Lease lease =
        new TranslationUnitLeaseRepository.Lease(
            jobId,
            unitId,
            revisionId,
            1,
            0,
            3,
            "worker-01",
            token,
            Instant.now().plusSeconds(30),
            Instant.now());
    when(repository.heartbeat(lease, "worker-01", token, 120)).thenReturn(Optional.of(lease));

    assertEquals(Optional.of(lease), service.heartbeat(lease, Duration.ofMinutes(2)));

    verify(repository).heartbeat(lease, "worker-01", token, 120);
  }
}
