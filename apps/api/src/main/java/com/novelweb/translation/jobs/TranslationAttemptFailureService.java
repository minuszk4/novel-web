package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationProviderException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates a worker failure before persisting its safe category and retry transition. */
@Service
@Profile("postgres")
public class TranslationAttemptFailureService {

  private final TranslationAttemptFailureRepository repository;

  public TranslationAttemptFailureService(TranslationAttemptFailureRepository repository) {
    this.repository = Objects.requireNonNull(repository, "attempt failure repository is required");
  }

  public TranslationAttemptFailureRepository.FailureResult record(
      TranslationUnitLeaseRepository.Lease lease,
      UUID attemptId,
      TranslationProviderException failure) {
    Objects.requireNonNull(lease, "current lease is required");
    Objects.requireNonNull(lease.jobId(), "job id is required");
    Objects.requireNonNull(lease.unitId(), "unit id is required");
    Objects.requireNonNull(lease.leaseToken(), "lease token is required");
    Objects.requireNonNull(attemptId, "attempt id is required");
    Objects.requireNonNull(failure, "provider failure is required");
    return repository.record(lease, attemptId, failure);
  }
}
