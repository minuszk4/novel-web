package com.novelweb.library.publication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases only due schedules after both the release and schedule worker gates are enabled. */
@Component
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.publication", name = "release-enabled", havingValue = "true")
@ConditionalOnProperty(
    prefix = "novel.publication.scheduler",
    name = "worker-enabled",
    havingValue = "true")
public class PublicationScheduleWorker {

  private final PublicationScheduleService scheduleService;

  public PublicationScheduleWorker(PublicationScheduleService scheduleService) {
    this.scheduleService = scheduleService;
  }

  @Scheduled(fixedDelayString = "${novel.publication.scheduler.poll-delay:15s}")
  public void poll() {
    scheduleService.processDueBatch();
  }
}
