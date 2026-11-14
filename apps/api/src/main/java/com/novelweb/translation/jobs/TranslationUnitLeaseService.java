package com.novelweb.translation.jobs;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates worker identity and lease bounds before delegating to PostgreSQL. */
@Service
@Profile("postgres")
public class TranslationUnitLeaseService {

  private static final String WORKER_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
  private static final long MIN_LEASE_SECONDS = 5;
  private static final long MAX_LEASE_SECONDS = 900;

  private final TranslationUnitLeaseRepository repository;

  public TranslationUnitLeaseService(TranslationUnitLeaseRepository repository) {
    this.repository = Objects.requireNonNull(repository, "lease repository is required");
  }

  public Optional<TranslationUnitLeaseRepository.Lease> claimNext(
      String workerId, Duration leaseDuration) {
    validateWorkerId(workerId);
    return repository.claimNext(workerId, leaseSeconds(leaseDuration));
  }

  public Optional<TranslationUnitLeaseRepository.Lease> heartbeat(
      TranslationUnitLeaseRepository.Lease lease, Duration leaseDuration) {
    Objects.requireNonNull(lease, "current lease is required");
    validateWorkerId(lease.workerId());
    Objects.requireNonNull(lease.jobId(), "job id is required");
    Objects.requireNonNull(lease.unitId(), "unit id is required");
    Objects.requireNonNull(lease.leaseToken(), "lease token is required");
    return repository.heartbeat(
        lease, lease.workerId(), lease.leaseToken(), leaseSeconds(leaseDuration));
  }

  private static void validateWorkerId(String workerId) {
    if (workerId == null || !workerId.matches(WORKER_ID_PATTERN)) {
      throw new IllegalArgumentException("worker id must be 1 to 128 safe ASCII characters");
    }
  }

  private static long leaseSeconds(Duration duration) {
    if (duration == null
        || duration.getNano() != 0
        || duration.getSeconds() < MIN_LEASE_SECONDS
        || duration.getSeconds() > MAX_LEASE_SECONDS) {
      throw new IllegalArgumentException(
          "lease duration must be a whole number from 5 to 900 seconds");
    }
    return duration.getSeconds();
  }
}
