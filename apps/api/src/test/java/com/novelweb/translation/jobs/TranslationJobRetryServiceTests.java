package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TranslationJobRetryServiceTests {

  private TranslationJobRetryRepository repository;
  private TranslationJobRetryService service;

  @BeforeEach
  void setUp() {
    repository = mock(TranslationJobRetryRepository.class);
    service = new TranslationJobRetryService(repository);
  }

  @Test
  void retryPassesOnlyAnActorAndJobScopedDigestToTheRepository() {
    UUID actorId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    String idempotencyKey = "translation-retry-key-0001";
    TranslationJobRetryRepository.RetryResult result =
        new TranslationJobRetryRepository.RetryResult(jobId, "queued", 1, false, UUID.randomUUID());
    when(repository.retryFailedUnits(eq(actorId), eq(jobId), org.mockito.ArgumentMatchers.any()))
        .thenReturn(result);

    assertEquals(result, service.retryFailedUnits(actorId, jobId, idempotencyKey));

    ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
    verify(repository).retryFailedUnits(eq(actorId), eq(jobId), digestCaptor.capture());
    String digest = digestCaptor.getValue();
    assertTrue(digest.matches("job-retry-[a-f0-9]{64}"));
    assertFalse(digest.contains(idempotencyKey));
  }

  @Test
  void retryRejectsMalformedIdempotencyKeysBeforeRepositoryAccess() {
    UUID actorId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();

    for (String idempotencyKey : List.of("short", "key with spaces", "x".repeat(129))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service.retryFailedUnits(actorId, jobId, idempotencyKey));
    }
    assertThrows(
        IllegalArgumentException.class, () -> service.retryFailedUnits(actorId, jobId, null));
    verifyNoInteractions(repository);
  }
}
