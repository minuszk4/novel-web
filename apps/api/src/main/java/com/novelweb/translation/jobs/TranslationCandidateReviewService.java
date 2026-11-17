package com.novelweb.translation.jobs;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Coordinates current manual-edit readiness and actor-scoped idempotent submission. */
@Service
@Profile("postgres")
public class TranslationCandidateReviewService {

  private final TranslationCandidateReviewRepository repository;

  public TranslationCandidateReviewService(TranslationCandidateReviewRepository repository) {
    this.repository =
        Objects.requireNonNull(repository, "translation candidate review repository is required");
  }

  public TranslationCandidateReviewRepository.Readiness readiness(UUID actorUserId, UUID jobId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    return repository.readReadiness(actorUserId, jobId);
  }

  public TranslationCandidateReviewRepository.SubmissionResult submit(
      UUID actorUserId, UUID jobId, String expectedEtag, String idempotencyKey) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    if (expectedEtag == null || !expectedEtag.matches("\"translation-review-[a-f0-9]{64}\"")) {
      throw new InvalidSnapshotEtagException();
    }
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}")) {
      throw new InvalidIdempotencyKeyException();
    }
    return repository.submit(
        actorUserId,
        jobId,
        expectedEtag,
        TranslationReviewSnapshotHasher.idempotencyDigest(idempotencyKey));
  }

  public static final class InvalidSnapshotEtagException extends RuntimeException {}

  public static final class InvalidIdempotencyKeyException extends RuntimeException {}
}
