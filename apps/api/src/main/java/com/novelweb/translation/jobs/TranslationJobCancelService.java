package com.novelweb.translation.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates an actor-scoped cancellation command and stores only a hashed idempotency key. */
@Service
@Profile("postgres")
public class TranslationJobCancelService {

  private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._~-]{16,128}";

  private final TranslationJobCancelRepository repository;

  public TranslationJobCancelService(TranslationJobCancelRepository repository) {
    this.repository =
        Objects.requireNonNull(repository, "translation cancellation repository is required");
  }

  public TranslationJobCancelRepository.CancelResult cancel(
      UUID actorUserId, UUID jobId, String idempotencyKey) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    if (idempotencyKey == null || !idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN)) {
      throw new IllegalArgumentException("Idempotency-Key must contain 16 to 128 safe characters");
    }
    return repository.cancel(
        actorUserId, jobId, deduplicationKey(actorUserId, jobId, idempotencyKey));
  }

  private static String deduplicationKey(UUID actorUserId, UUID jobId, String idempotencyKey) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  String.join("\n", actorUserId.toString(), jobId.toString(), idempotencyKey)
                      .getBytes(StandardCharsets.UTF_8));
      return "job-cancel-" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
