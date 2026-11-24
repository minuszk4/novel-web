package com.novelweb.library.publication;

import com.novelweb.library.publication.PublicationScheduleRepository.CancelOutcome;
import com.novelweb.library.publication.PublicationScheduleRepository.ScheduleClaim;
import com.novelweb.library.publication.PublicationScheduleRepository.ScheduleView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Owner schedule commands and revalidated due-release processing. */
@Service
@Profile("postgres")
public class PublicationScheduleService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PublicationScheduleService.class);

  private final PublicationReleaseService releaseService;
  private final PublicationScheduleRepository repository;

  public PublicationScheduleService(
      PublicationReleaseService releaseService, PublicationScheduleRepository repository) {
    this.releaseService = releaseService;
    this.repository = repository;
  }

  public PublicationReleaseService.ScheduleResult schedule(
      UUID actorId, UUID workId, String ifMatch, Instant scheduledAt, String idempotencyKey) {
    return releaseService.schedule(actorId, workId, ifMatch, scheduledAt, idempotencyKey);
  }

  public CancelOutcome cancel(UUID actorId, UUID workId, UUID scheduleId) {
    return repository.cancel(actorId, workId, scheduleId);
  }

  public Optional<ScheduleView> get(UUID actorId, UUID workId, UUID scheduleId) {
    return repository.findForOwner(actorId, workId, scheduleId);
  }

  public Optional<ScheduleView> getActive(UUID actorId, UUID workId) {
    return repository.findActiveForOwner(actorId, workId);
  }

  public int processDueBatch() {
    List<ScheduleClaim> claims = repository.claimDueBatch();
    int completed = 0;
    for (ScheduleClaim claim : claims) {
      try {
        PublicationReleaseService.ReleaseResult result =
            releaseService.publishNow(
                claim.ownerId(),
                claim.workId(),
                claim.readinessEtag(),
                "publication-schedule-" + claim.scheduleId());
        switch (result.outcome()) {
          case CREATED, REPLAYED -> {
            repository.markPublished(claim, result.receipt().eventId());
            completed++;
          }
          case STALE -> {
            repository.markBlocked(claim, "PUBLICATION_SNAPSHOT_STALE");
            completed++;
          }
          case ALREADY_PUBLISHED -> {
            Optional<UUID> releaseEventId =
                releaseService.findReleaseEventIdForRequest(claim.requestId());
            if (releaseEventId.isPresent()) {
              repository.markPublished(claim, releaseEventId.orElseThrow());
            } else {
              repository.markBlocked(claim, "PUBLICATION_STATE_CHANGED");
            }
            completed++;
          }
          case KEY_REUSED,
              WORK_NOT_FOUND,
              APPROVAL_REQUIRED,
              POLICY_REQUIRED,
              POLICY_NOT_CONFIGURED,
              RIGHTS_POLICY_NOT_CONFIGURED,
              RIGHTS_POLICY_NOT_ACCEPTED,
              RIGHTS_POLICY_STALE,
              APPROVAL_CHANGED -> {
            repository.markBlocked(claim, "PUBLICATION_STATE_CHANGED");
            completed++;
          }
        }
      } catch (IllegalStateException exception) {
        LOGGER.warn("Scheduled publication attempt will retry after its database lease expires");
      }
    }
    return completed;
  }
}
