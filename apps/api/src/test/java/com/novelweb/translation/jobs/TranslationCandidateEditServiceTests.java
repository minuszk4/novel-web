package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranslationCandidateEditServiceTests {

  private TranslationCandidateEditRepository repository;
  private TranslationCandidateEditService service;
  private UUID actorId;
  private UUID jobId;
  private UUID segmentId;

  @BeforeEach
  void setUp() {
    repository = mock(TranslationCandidateEditRepository.class);
    service = new TranslationCandidateEditService(repository);
    actorId = UUID.randomUUID();
    jobId = UUID.randomUUID();
    segmentId = UUID.randomUUID();
  }

  @Test
  void rejectsBlankTextUnsafeKeyAndTextAboveUtf8ByteLimitBeforeRepositoryCall() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.save(actorId, jobId, segmentId, 0, "translation-edit-key-0001", " \n\t"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.save(actorId, jobId, segmentId, 0, "not a safe key", "Corrected text."));
    String overLimit = "界".repeat(349_526);
    assertEquals(1_048_578, overLimit.getBytes(StandardCharsets.UTF_8).length);
    assertThrows(
        TranslationCandidateEditService.EditTextTooLargeException.class,
        () -> service.save(actorId, jobId, segmentId, 0, "translation-edit-key-0002", overLimit));
    verifyNoInteractions(repository);
  }

  @Test
  void hashesTextAndIdempotencyKeyAndAllowsExactlyOneMebibyte() throws Exception {
    String key = "translation-edit-key-0003";
    String text = "a".repeat(1_048_576);
    var expected =
        new TranslationCandidateEditRepository.EditResult(
            jobId, UUID.randomUUID(), segmentId, 1, text, Instant.now(), false);
    when(repository.appendEdit(
            eq(actorId),
            eq(jobId),
            eq(segmentId),
            eq(0),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class),
            eq(text)))
        .thenReturn(expected);

    TranslationCandidateEditRepository.EditResult result =
        service.save(actorId, jobId, segmentId, 0, key, text);

    assertEquals(expected, result);
    var idempotencyKeyHash = org.mockito.ArgumentCaptor.forClass(byte[].class);
    var requestHash = org.mockito.ArgumentCaptor.forClass(byte[].class);
    var textHash = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(repository)
        .appendEdit(
            eq(actorId),
            eq(jobId),
            eq(segmentId),
            eq(0),
            idempotencyKeyHash.capture(),
            requestHash.capture(),
            textHash.capture(),
            eq(text));
    assertArrayEquals(
        MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII)),
        idempotencyKeyHash.getValue());
    assertEquals(32, requestHash.getValue().length);
    assertArrayEquals(
        MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)),
        textHash.getValue());
  }
}
