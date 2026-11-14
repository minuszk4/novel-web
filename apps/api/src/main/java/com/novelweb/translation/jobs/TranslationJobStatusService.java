package com.novelweb.translation.jobs;

import java.util.List;
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
  private final TranslationJobCursorCodec cursorCodec;

  public TranslationJobStatusService(
      TranslationJobStatusRepository repository, TranslationJobCursorCodec cursorCodec) {
    this.repository =
        Objects.requireNonNull(repository, "translation status repository is required");
    this.cursorCodec =
        Objects.requireNonNull(cursorCodec, "translation job cursor codec is required");
  }

  public Optional<StatusResult> findVisible(UUID actorUserId, UUID jobId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    return repository
        .findVisible(actorUserId, jobId)
        .map(TranslationJobStatusService::statusResult);
  }

  public JobPage listManageable(UUID actorUserId, String cursor, int requestedLimit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (requestedLimit < 1 || requestedLimit > 50) {
      throw new IllegalArgumentException("limit must be between 1 and 50");
    }
    cursorCodec.requireConfigured();
    TranslationJobCursorCodec.Position position =
        cursor == null ? null : cursorCodec.decode(actorUserId, cursor);
    TranslationJobStatusRepository.Slice slice =
        repository.findManageable(actorUserId, position, requestedLimit + 1);
    boolean hasMore = slice.items().size() > requestedLimit;
    List<StatusResult> jobs =
        slice.items().stream()
            .limit(requestedLimit)
            .map(TranslationJobStatusService::statusResult)
            .toList();
    String nextCursor =
        hasMore
            ? cursorCodec.encode(
                actorUserId,
                new TranslationJobCursorCodec.Position(
                    jobs.getLast().job().createdAt(), jobs.getLast().job().id()))
            : null;
    return new JobPage(jobs, nextCursor);
  }

  private static StatusResult statusResult(TranslationJobStatusRepository.JobStatus job) {
    int terminalUnits = job.completedUnits() + job.failedUnits() + job.cancelledUnits();
    int progressPercent =
        job.totalUnits() == 0 ? 0 : Math.min(100, terminalUnits * 100 / job.totalUnits());
    return new StatusResult(job, terminalUnits, progressPercent);
  }

  public record StatusResult(
      TranslationJobStatusRepository.JobStatus job, int terminalUnits, int progressPercent) {}

  public record JobPage(List<StatusResult> items, String nextCursor) {

    public JobPage {
      items = List.copyOf(items);
    }
  }
}
