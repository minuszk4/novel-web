package com.novelweb.translation.jobs;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Builds a redacted, actor-visible translation job progress view. */
@Service
@Profile("postgres")
public class TranslationJobStatusService {

  private final TranslationJobStatusRepository repository;

  public TranslationJobStatusService(TranslationJobStatusRepository repository) {
    this.repository =
        Objects.requireNonNull(repository, "translation status repository is required");
  }

  public Optional<StatusResult> findVisible(UUID actorUserId, UUID jobId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    return repository
        .findVisible(actorUserId, jobId)
        .map(
            job -> {
              int terminalUnits = job.completedUnits() + job.failedUnits() + job.cancelledUnits();
              int progressPercent =
                  job.totalUnits() == 0 ? 0 : Math.min(100, terminalUnits * 100 / job.totalUnits());
              return new StatusResult(job, terminalUnits, progressPercent);
            });
  }

  public record StatusResult(
      TranslationJobStatusRepository.JobStatus job, int terminalUnits, int progressPercent) {}
}
