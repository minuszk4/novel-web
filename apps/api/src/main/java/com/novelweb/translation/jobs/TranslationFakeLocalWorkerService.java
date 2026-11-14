package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationProviderException;
import com.novelweb.translation.provider.TranslationProviderExecutionGate;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Runs one bounded job unit using only the explicitly configured offline fake provider. */
@Service
@Profile("postgres & translation-fake")
@ConditionalOnProperty(prefix = "novel.translation.fake", name = "enabled", havingValue = "true")
public class TranslationFakeLocalWorkerService {

  private final TranslationUnitLeaseService leaseService;
  private final TranslationAttemptWorkRepository workRepository;
  private final TranslationAttemptService attemptService;
  private final TranslationProviderExecutionGate executionGate;
  private final TranslationAttemptCompletionService completionService;

  public TranslationFakeLocalWorkerService(
      TranslationUnitLeaseService leaseService,
      TranslationAttemptWorkRepository workRepository,
      TranslationAttemptService attemptService,
      TranslationProviderExecutionGate executionGate,
      TranslationAttemptCompletionService completionService) {
    this.leaseService = Objects.requireNonNull(leaseService, "lease service is required");
    this.workRepository = Objects.requireNonNull(workRepository, "work repository is required");
    this.attemptService = Objects.requireNonNull(attemptService, "attempt service is required");
    this.executionGate = Objects.requireNonNull(executionGate, "execution gate is required");
    this.completionService =
        Objects.requireNonNull(completionService, "completion service is required");
  }

  public Optional<WorkResult> processNext(String workerId, Duration leaseDuration)
      throws TranslationProviderException {
    Optional<TranslationUnitLeaseRepository.Lease> lease =
        leaseService.claimNext(workerId, leaseDuration);
    if (lease.isEmpty()) return Optional.empty();
    return Optional.of(process(lease.orElseThrow()));
  }

  public WorkResult process(TranslationUnitLeaseRepository.Lease lease)
      throws TranslationProviderException {
    Objects.requireNonNull(lease, "current lease is required");
    TranslationAttemptWorkRepository.WorkSnapshot snapshot = workRepository.load(lease);
    TranslationAttemptRepository.StartResult attempt = attemptService.begin(lease);
    if (!attempt.dispatchAllowed()) {
      return new WorkResult(
          attempt.jobId(), attempt.unitId(), attempt.attemptId(), null, false, true);
    }
    TranslationProviderExecutionGate.TranslationExecutionResult execution =
        executionGate.execute(snapshot.toRequest(attempt.attemptId()));
    TranslationAttemptCompletionRepository.CompletionResult completion =
        completionService.completeExecution(lease, execution);
    return new WorkResult(
        completion.jobId(),
        completion.unitId(),
        completion.attemptId(),
        completion,
        true,
        attempt.replayed());
  }

  public record WorkResult(
      UUID jobId,
      UUID unitId,
      UUID attemptId,
      TranslationAttemptCompletionRepository.CompletionResult completion,
      boolean dispatched,
      boolean resumed) {}
}
