package com.novelweb.translation.jobs;

import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Starts a durable attempt exactly once under the worker's current live lease. */
@Service
@Profile("postgres")
public class TranslationAttemptService {

  private final TranslationAttemptRepository repository;

  public TranslationAttemptService(TranslationAttemptRepository repository) {
    this.repository = Objects.requireNonNull(repository, "attempt repository is required");
  }

  public TranslationAttemptRepository.StartResult begin(
      TranslationUnitLeaseRepository.Lease lease) {
    Objects.requireNonNull(lease, "current lease is required");
    Objects.requireNonNull(lease.jobId(), "job id is required");
    Objects.requireNonNull(lease.unitId(), "unit id is required");
    Objects.requireNonNull(lease.sourceRevisionId(), "source revision id is required");
    Objects.requireNonNull(lease.workerId(), "worker id is required");
    Objects.requireNonNull(lease.leaseToken(), "lease token is required");
    return repository.begin(lease);
  }
}
