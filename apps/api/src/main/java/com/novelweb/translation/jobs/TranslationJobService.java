package com.novelweb.translation.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates an offline translation-job request before storing its durable intent. */
@Service
@Profile("postgres")
public class TranslationJobService {

  private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._~-]{16,128}";
  private static final String FAKE_LOCAL_PROVIDER_ID = "fake-local";

  private final TranslationJobRepository repository;

  public TranslationJobService(TranslationJobRepository repository) {
    this.repository = Objects.requireNonNull(repository, "translation job repository is required");
  }

  public EnqueueResult enqueue(
      UUID actorUserId,
      UUID chapterId,
      UUID sourceRevisionId,
      String targetLanguage,
      String idempotencyKey) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    Objects.requireNonNull(sourceRevisionId, "source revision id is required");
    if (idempotencyKey == null || !idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN)) {
      throw new IllegalArgumentException("Idempotency-Key must contain 16 to 128 safe characters");
    }
    String canonicalTargetLanguage = canonicalLanguage(targetLanguage, "target language");
    byte[] requestSha256 =
        requestHash(chapterId, sourceRevisionId, canonicalTargetLanguage, FAKE_LOCAL_PROVIDER_ID);
    return repository.enqueue(
        new TranslationJobRepository.EnqueueCommand(
            actorUserId,
            chapterId,
            sourceRevisionId,
            canonicalTargetLanguage,
            FAKE_LOCAL_PROVIDER_ID,
            idempotencyKey,
            requestSha256));
  }

  private static String canonicalLanguage(String value, String fieldName) {
    if (value == null || value.isBlank() || value.length() > 35 || !value.equals(value.trim())) {
      throw new IllegalArgumentException(fieldName + " must be a valid language tag");
    }
    try {
      String canonical = new Locale.Builder().setLanguageTag(value).build().toLanguageTag();
      if (!canonical.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) {
        throw new IllegalArgumentException(fieldName + " must be a valid language tag");
      }
      return canonical;
    } catch (java.util.IllformedLocaleException exception) {
      throw new IllegalArgumentException(fieldName + " must be a valid language tag");
    }
  }

  private static byte[] requestHash(
      UUID chapterId, UUID sourceRevisionId, String targetLanguage, String providerId) {
    String canonicalRequest =
        String.join(
            "\n",
            "translation-job-enqueue-v1",
            chapterId.toString(),
            sourceRevisionId.toString(),
            targetLanguage,
            providerId,
            "profile:{}",
            "glossary:[]");
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record EnqueueResult(TranslationJobRepository.Job job, boolean replayed) {}
}
