package com.novelweb.translation.jobs;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates and appends an author's private correction to a fake-local candidate segment. */
@Service
@Profile("postgres")
public class TranslationCandidateEditService {

  private static final int MAX_TRANSLATED_TEXT_UTF8_BYTES = 1_048_576;
  private static final byte[] REQUEST_DOMAIN =
      "translation-candidate-edit-v1\0".getBytes(StandardCharsets.UTF_8);

  private final TranslationCandidateEditRepository repository;

  public TranslationCandidateEditService(TranslationCandidateEditRepository repository) {
    this.repository =
        Objects.requireNonNull(repository, "translation candidate edit repository is required");
  }

  public TranslationCandidateEditRepository.EditResult save(
      UUID actorUserId,
      UUID jobId,
      UUID sourceSegmentId,
      int expectedEditRevision,
      String idempotencyKey,
      String translatedText) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    Objects.requireNonNull(sourceSegmentId, "source segment id is required");
    if (expectedEditRevision < 0) {
      throw new IllegalArgumentException("expected edit revision must not be negative");
    }
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}")) {
      throw new IllegalArgumentException(
          "Idempotency-Key must contain 16 to 128 safe ASCII characters");
    }
    if (translatedText == null || translatedText.isBlank()) {
      throw new IllegalArgumentException("translated text must not be blank");
    }
    byte[] translatedTextBytes = translatedText.getBytes(StandardCharsets.UTF_8);
    if (translatedTextBytes.length > MAX_TRANSLATED_TEXT_UTF8_BYTES) {
      throw new EditTextTooLargeException();
    }
    byte[] requestHash = requestHash(expectedEditRevision, translatedTextBytes);
    return repository.appendEdit(
        actorUserId,
        jobId,
        sourceSegmentId,
        expectedEditRevision,
        sha256(idempotencyKey.getBytes(StandardCharsets.US_ASCII)),
        requestHash,
        sha256(translatedTextBytes),
        translatedText);
  }

  private static byte[] requestHash(int expectedEditRevision, byte[] translatedTextBytes) {
    MessageDigest digest = newDigest();
    digest.update(REQUEST_DOMAIN);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(expectedEditRevision).array());
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(translatedTextBytes.length).array());
    digest.update(translatedTextBytes);
    return digest.digest();
  }

  private static byte[] sha256(byte[] value) {
    return newDigest().digest(value);
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static final class EditTextTooLargeException extends RuntimeException {}
}
