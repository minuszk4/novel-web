package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationResponse;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates completion inputs before delegating the lease-fenced PostgreSQL transaction. */
@Service
@Profile("postgres")
public class TranslationAttemptCompletionService {

  private final TranslationAttemptCompletionRepository repository;

  public TranslationAttemptCompletionService(TranslationAttemptCompletionRepository repository) {
    this.repository = Objects.requireNonNull(repository, "completion repository is required");
  }

  public TranslationAttemptCompletionRepository.CompletionResult complete(
      TranslationUnitLeaseRepository.Lease lease, TranslationResponse response) {
    Objects.requireNonNull(lease, "current lease is required");
    Objects.requireNonNull(lease.jobId(), "job id is required");
    Objects.requireNonNull(lease.unitId(), "unit id is required");
    Objects.requireNonNull(lease.sourceRevisionId(), "source revision id is required");
    Objects.requireNonNull(lease.workerId(), "worker id is required");
    Objects.requireNonNull(lease.leaseToken(), "lease token is required");
    Objects.requireNonNull(response, "provider response is required");
    Objects.requireNonNull(response.attemptId(), "attempt id is required");
    if (lease.attemptCount() < 0 || lease.attemptCount() >= lease.maxAttempts()) {
      throw new IllegalArgumentException("lease attempt count is outside its allowed range");
    }
    return repository.complete(lease, response);
  }
}
