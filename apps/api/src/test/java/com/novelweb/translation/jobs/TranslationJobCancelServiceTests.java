package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

class TranslationJobCancelServiceTests {

  private TranslationJobCancelRepository repository;
  private TranslationJobCancelService service;

  @BeforeEach
  void setUp() {
    repository = mock(TranslationJobCancelRepository.class);
    service = new TranslationJobCancelService(repository);
  }

  @Test
  void cancelPassesOnlyAnActorAndJobScopedDigestToTheRepository() {
    UUID actorId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    String idempotencyKey = "translation-cancel-key-0001";
    TranslationJobCancelRepository.CancelResult result =
        new TranslationJobCancelRepository.CancelResult(
            jobId, "cancelled", 1, 0, false, UUID.randomUUID());
    when(repository.cancel(eq(actorId), eq(jobId), any())).thenReturn(result);

    assertEquals(result, service.cancel(actorId, jobId, idempotencyKey));

    ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
    verify(repository).cancel(eq(actorId), eq(jobId), digestCaptor.capture());
    String digest = digestCaptor.getValue();
    assertTrue(digest.matches("job-cancel-[a-f0-9]{64}"));
    assertFalse(digest.contains(idempotencyKey));
  }

  @Test
  void cancelRejectsMalformedIdempotencyKeysBeforeRepositoryAccess() {
    UUID actorId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();

    for (String idempotencyKey : List.of("short", "key with spaces", "x".repeat(129))) {
      assertThrows(
          IllegalArgumentException.class, () -> service.cancel(actorId, jobId, idempotencyKey));
    }
    assertThrows(IllegalArgumentException.class, () -> service.cancel(actorId, jobId, null));
    verifyNoInteractions(repository);
  }
}
